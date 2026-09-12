package com.wanderwildwood.kotozute.signalstore

import org.signal.core.models.ServiceId
import org.signal.network.config.SignalServiceConfiguration
import org.whispersystems.signalservice.api.SignalServiceMessageSender
import org.whispersystems.signalservice.api.SignalSessionLock
import org.whispersystems.signalservice.api.crypto.ContentHint
import org.whispersystems.signalservice.api.messages.SendMessageResult
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentStream
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.messages.SignalServiceReceiptMessage
import org.whispersystems.signalservice.api.messages.multidevice.BlockedListMessage
import org.whispersystems.signalservice.api.messages.multidevice.RequestMessage
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.message.MessageApi
import org.whispersystems.signalservice.api.util.CredentialsProvider
import org.whispersystems.signalservice.internal.push.PushServiceSocket
import org.whispersystems.signalservice.api.keys.PreKeyRepository
import timber.log.Timber
import java.util.Optional
import java.util.concurrent.Executors

/**
 * Sends messages from this device.
 *
 * The other half of what the bridge did. A linked device sends on its own authority -- the
 * primary is not consulted and is not in the path -- which is why this needs the same protocol
 * store the receive side uses: sending builds sessions, consumes the recipient's pre keys and
 * writes ratchet state, exactly as receiving does.
 */
internal class SignalSender(
    private val configuration: SignalServiceConfiguration,
    private val userAgent: String,
    private val accounts: SignalAccountStore,
    private val db: ProtocolDatabase,
    private val protocol: SignalDataStore,
    private val connection: SignalConnection,
    private val contacts: SignalContactStore
) {

    private val sealedSender by lazy { SealedSender(connection, contacts) }

    /** The store's own lock, for the same reason the receiver uses it. See [SignalReceiver]. */
    private val sessionLock = SignalSessionLock {
        db.lock.lock()
        SignalSessionLock.Lock { db.lock.unlock() }
    }

    private val credentials = object : CredentialsProvider {
        override fun getAci(): ServiceId.ACI? = ServiceId.ACI.parseOrNull(accounts.credentials().aci)
        override fun getPni(): ServiceId.PNI? = ServiceId.PNI.parseOrNull(accounts.credentials().pni)
        override fun getE164(): String? = accounts.credentials().e164
        override fun getDeviceId(): Int = accounts.credentials().deviceId
        override fun getPassword(): String? = accounts.credentials().password
    }

    private val sender: SignalServiceMessageSender by lazy {
        val socket = PushServiceSocket(configuration, credentials, userAgent, true)
        val aci = credentials.aci ?: error("not linked")
        SignalServiceMessageSender(
            socket,
            protocol,
            sessionLock,
            MessageApi(connection.authenticated, connection.unauthenticated),
            connection.keys,
            Optional.empty(),
            // One thread. Sends against one recipient must not interleave: two in flight
            // against the same device race on the ratchet, and the loser produces a message
            // the far end cannot decrypt.
            Executors.newSingleThreadExecutor(),
            MAX_ENVELOPE_SIZE,
            MAX_INCREMENTAL_MACS_PER_ENVELOPE,
            { true },
            PreKeyRepository(
                connection.keys,
                protocol.aci(),
                aci.toProtocolAddress(credentials.deviceId),
                sessionLock,
                // Runs the block directly. The hook exists so a client can defer the
                // bookkeeping that follows an identity change -- storage sync, live recipient
                // updates -- while a batch of sessions is built. This app has none of that
                // bookkeeping, so there is nothing to defer. signal-cli passes the equivalent.
                PreKeyRepository.BatchHelper { it.run() }
            )
        )
    }

    /**
     * Sends to every member of a group.
     *
     * Fan-out: the message is encrypted to each member separately, with the group's context
     * attached so their clients file it in the right conversation. Signal's own clients prefer
     * sender keys, which encrypt once and let the server fan out -- cheaper for large groups,
     * and a whole distribution mechanism to get right. This is the path Signal itself falls
     * back to when sender keys are not usable, and for a group of a few people the difference
     * is bandwidth rather than behaviour.
     *
     * @return the timestamp on success, or a failure naming who it could not reach. Partial
     *   delivery is reported as failure: saying "sent" when one member did not get it is the
     *   kind of lie that only shows up later, in an argument about who said what.
     */
    fun sendToGroup(
        masterKey: ByteArray,
        members: List<ServiceId>,
        body: String,
        expiresInSeconds: Int = 0,
        expireTimerVersion: Int = 0
    ): Result {
        if (members.isEmpty()) return Result.Failed("the group has no members this device can reach")
        val timestamp = System.currentTimeMillis()

        val group = org.whispersystems.signalservice.api.messages.SignalServiceGroupV2
            .newBuilder(org.signal.libsignal.zkgroup.groups.GroupMasterKey(masterKey))
            .withRevision(0)
            .build()

        val message = SignalServiceDataMessage.newBuilder()
            .withBody(body)
            .withTimestamp(timestamp)
            .asGroupMessage(group)
            // The group's own timer. Sent with every message, as Signal does: a message with
            // no timer is not "unspecified", it is a timer of zero, and a group that had
            // agreed its messages disappear would quietly stop expiring ours.
            .withExpiration(expiresInSeconds)
            .withExpireTimerVersion(expireTimerVersion)
            .build()

        return try {
            val results = sender.sendDataMessage(
                members.map { SignalServiceAddress(it) },
                members.map { sealedSender.accessFor(it.toString()) },
                false,
                ContentHint.RESENDABLE,
                message,
                SignalServiceMessageSender.LegacyGroupEvents.EMPTY,
                null,
                null,
                // urgent. The server uses this to decide whether to wake a dozing
                // recipient with a high-priority push. Sent non-urgent, an ordinary
                // message waits until their phone next connects on its own schedule --
                // which, on a phone built to stay asleep, is exactly the case where
                // somebody would say the message never arrived.
                true
            )
            val failed = results.filterNot { it.isSuccess }
            if (failed.isEmpty()) {
                Timber.i("signal send: delivered to %d group members ts=%d", results.size, timestamp)
                Result.Sent(timestamp)
            } else {
                Result.Failed("could not reach ${failed.size} of ${results.size} group members")
            }
        } catch (t: Throwable) {
            Timber.w(t, "signal send: group send threw")
            Result.Failed(t.message ?: t::class.java.simpleName)
        }
    }

    /**
     * Asks the primary to send its contacts.
     *
     * A linked device starts knowing nobody: it has no address book and cannot resolve an ACI
     * on its own. The primary answers with a sync message carrying a blob, which arrives like
     * any other message and is handled on the receive side.
     *
     * A request, not a query -- there is no reply to wait for here. The answer comes back
     * minutes or seconds later through the socket, so this returns as soon as the ask is sent.
     */
    fun requestContactsSync(): Result {
        val request = org.whispersystems.signalservice.internal.push.SyncMessage.Request.Builder()
            .type(org.whispersystems.signalservice.internal.push.SyncMessage.Request.Type.CONTACTS)
            .build()
        return try {
            val result = sender.sendSyncMessage(
                org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage.forRequest(
                    org.whispersystems.signalservice.api.messages.multidevice.RequestMessage(request)
                )
            )
            if (result.isSuccess) {
                Timber.i("signal contacts: requested a sync from the primary")
                Result.Sent(System.currentTimeMillis())
            } else {
                Result.Failed("the primary refused the contacts request")
            }
        } catch (t: Throwable) {
            Timber.w(t, "signal contacts: request threw")
            Result.Failed(t.message ?: t::class.java.simpleName)
        }
    }

    sealed interface Result {
        data class Sent(val timestamp: Long) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Sends [body] to one recipient.
     *
     * The timestamp is the message's identity, not a decoration: it is half of the
     * `(author, timestamp)` pair every other device uses to recognise this message, including
     * our own other devices when this send comes back to them as a sync. So it is generated
     * once, here, and returned -- not read back from anything.
     */
    /**
     * A reaction: an emoji hung on somebody else's message rather than a message of its own.
     *
     * Signal names the message being reacted to by who wrote it and when they sent it, so
     * [targetAuthor] is the *author of that message* -- this account when the reaction is to
     * something we sent ourselves, which is the case that reads wrong if it is guessed.
     */
    /**
     * Sends the account's blocked list, which is how a linked device changes it.
     *
     * ⚠ **This replaces the account's list with what is passed.** There is no "block one
     * more" message in Signal's protocol: the sync carries every blocked party, and the
     * primary takes it as the truth. Whatever is missing here becomes unblocked everywhere.
     * The caller must have a list from the primary to edit -- see [SignalBlockStore.known].
     */
    fun sendBlockedList(
        individuals: List<BlockedListMessage.Individual>,
        groups: List<BlockedListMessage.Group>
    ): Result = try {
        val result = sender.sendSyncMessage(
            SignalServiceSyncMessage.forBlocked(BlockedListMessage(individuals, groups))
        )
        if (result.isSuccess) {
            Timber.i("signal blocked: sent a list of %d", individuals.size)
            Result.Sent(System.currentTimeMillis())
        } else {
            Result.Failed(describe(result))
        }
    } catch (t: Throwable) {
        Timber.w(t, "signal blocked: sending the list threw")
        Result.Failed(t.message ?: t::class.java.simpleName)
    }

    /**
     * Tells somebody their messages have been read.
     *
     * Only ever called where the reader has asked for receipts to be sent: this is the one
     * message here that exists to tell another person something about the reader rather than
     * to carry anything they wrote.
     */
    fun sendReadReceipt(recipient: ServiceId, timestamps: List<Long>): Result =
        sendReceipt(recipient, timestamps, SignalServiceReceiptMessage.Type.READ, "a read receipt")

    /**
     * Tells somebody their message arrived.
     *
     * Not a setting and not a courtesy: Signal's clients send this for every message they
     * receive, and it is the only thing that ever turns a sender's "sent" into "delivered".
     * It says nothing about whether anybody has looked -- that is [sendReadReceipt], which is
     * a choice the reader makes.
     */
    fun sendDeliveryReceipt(recipient: ServiceId, timestamps: List<Long>): Result =
        sendReceipt(recipient, timestamps, SignalServiceReceiptMessage.Type.DELIVERY, "a delivery receipt")

    /**
     * Asks somebody to send a message again, because this phone could not read it.
     *
     * Signal's answer to a message that will not decrypt. The receipt carries enough for the
     * sender to identify the exact message and to see that the session is broken; their client
     * then archives the session and sends it again over a fresh one. Without it a message that
     * fails to decrypt is simply lost, and the only trace is a row saying one could not be
     * read -- which is what this app had.
     *
     * Not retried and not repaired here. If a retry receipt cannot be sent, the message it was
     * about stays unread, which is exactly the state it was already in; sending it twice would
     * ask the far end to resend twice.
     */
    fun sendRetryReceipt(
        recipient: ServiceId,
        error: org.signal.libsignal.protocol.message.DecryptionErrorMessage,
        groupId: ByteArray?
    ): Result = try {
        val result = sender.sendRetryReceipt(
            SignalServiceAddress(recipient),
            sealedSender.accessFor(recipient.toString()),
            java.util.Optional.ofNullable(groupId),
            error
        )
        if (result.isSuccess) Result.Sent(System.currentTimeMillis()) else Result.Failed(describe(result))
    } catch (t: Throwable) {
        Timber.w(t, "signal retry: could not ask for a message to be sent again")
        Result.Failed(t.message ?: t::class.java.simpleName)
    }

    /**
     * One receipt send, with the one repair that is worth making.
     *
     * A receipt is sent inside an existing session, and a session can go stale -- the far end
     * reinstalled, or archived its own. libsignal then throws `NoSessionException`, and
     * catching that alongside everything else means the receipt is dropped for good: the
     * sender's message stays "sent" forever, which is exactly the state a delivery receipt
     * exists to leave. Archiving the local session forces a fresh one on the retry.
     *
     * Once only. If it fails again the session is not the problem, and a loop here would sit
     * between the far end and every later receipt.
     *
     * The repair is Signal Android's, from `ReceiptSender.sendWithSessionRepair`.
     */
    private fun sendReceipt(
        recipient: ServiceId,
        timestamps: List<Long>,
        type: SignalServiceReceiptMessage.Type,
        what: String
    ): Result {
        fun attempt() = sender.sendReceipt(
            SignalServiceAddress(recipient),
            sealedSender.accessFor(recipient.toString()),
            SignalServiceReceiptMessage(type, timestamps, System.currentTimeMillis()),
            false
        )
        return try {
            val result = try {
                attempt()
            } catch (missing: org.signal.libsignal.protocol.NoSessionException) {
                Timber.w(missing, "signal receipt: no session for %s, archiving and retrying", what)
                archiveSessions(recipient)
                attempt()
            }
            if (result.isSuccess) Result.Sent(System.currentTimeMillis())
            else Result.Failed(describe(result))
        } catch (t: Throwable) {
            Timber.w(t, "signal receipt: sending %s threw", what)
            Result.Failed(t.message ?: t::class.java.simpleName)
        }
    }

    /**
     * Forgets the local half of every session with somebody, so the next send builds a new one.
     *
     * Every device, not only the one that failed: a receipt goes to all of them, and leaving a
     * stale session on any other device reproduces the failure on the next attempt.
     */
    private fun archiveSessions(recipient: ServiceId) {
        runCatching {
            val store = protocol.aci()
            store.getSubDeviceSessions(recipient.toString())
                .plus(DEFAULT_DEVICE_ID)
                .forEach { device ->
                    store.archiveSession(
                        org.signal.libsignal.protocol.SignalProtocolAddress(recipient.toString(), device)
                    )
                }
        }.onFailure { Timber.w(it, "signal receipt: could not archive the stale sessions") }
    }

    /**
     * Asks the primary for the account's key material. The answer arrives later, through the
     * socket, and only the storage service key is kept from it.
     */
    fun requestKeys(): Result = try {
        val result = sender.sendSyncMessage(
            SignalServiceSyncMessage.forRequest(
                RequestMessage.forType(
                    org.whispersystems.signalservice.internal.push.SyncMessage.Request.Type.KEYS
                )
            )
        )
        if (result.isSuccess) Result.Sent(System.currentTimeMillis()) else Result.Failed(describe(result))
    } catch (t: Throwable) {
        Timber.w(t, "signal keys: requesting them threw")
        Result.Failed(t.message ?: t::class.java.simpleName)
    }

    /** Asks the primary for the blocked list, which arrives later through the socket. */
    fun requestBlockedList(): Result = try {
        val result = sender.sendSyncMessage(
            SignalServiceSyncMessage.forRequest(
                RequestMessage.forType(
                    org.whispersystems.signalservice.internal.push.SyncMessage.Request.Type.BLOCKED
                )
            )
        )
        if (result.isSuccess) Result.Sent(System.currentTimeMillis()) else Result.Failed(describe(result))
    } catch (t: Throwable) {
        Timber.w(t, "signal blocked: requesting the list threw")
        Result.Failed(t.message ?: t::class.java.simpleName)
    }

    /**
     * Why a send did not land, in the terms that matter. Worth separating: an identity
     * failure is not a network problem -- the recipient's safety number changed, and
     * retrying sends to a key this device has already refused to trust.
     */
    private fun describe(result: SendMessageResult): String = when {
        result.identityFailure != null -> "identity changed for ${result.address.serviceId}"
        result.isUnregisteredFailure -> "${result.address.serviceId} is not registered"
        result.isNetworkFailure -> "network failure sending to ${result.address.serviceId}"
        result.isInvalidPreKeyFailure -> "${result.address.serviceId} has an unusable pre key"
        result.rateLimitFailure != null -> "rate limited"
        result.proofRequiredFailure != null -> "the server wants a proof of humanity"
        else -> "send failed for an unreported reason"
    }

    fun sendReaction(
        recipient: ServiceId,
        emoji: String,
        remove: Boolean,
        targetAuthor: ServiceId,
        targetSentTimestamp: Long
    ): Result {
        val timestamp = System.currentTimeMillis()
        val message = SignalServiceDataMessage.newBuilder()
            .withTimestamp(timestamp)
            .withReaction(
                SignalServiceDataMessage.Reaction(emoji, remove, targetAuthor, targetSentTimestamp)
            )
            .build()

        return try {
            val result = sender.sendDataMessage(
                SignalServiceAddress(recipient),
                sealedSender.accessFor(recipient.toString()),
                ContentHint.RESENDABLE,
                message,
                SignalServiceMessageSender.IndividualSendEvents.EMPTY,
                false,
                // urgent. The server uses this to decide whether to wake a dozing
                // recipient with a high-priority push. Sent non-urgent, an ordinary
                // message waits until their phone next connects on its own schedule --
                // which, on a phone built to stay asleep, is exactly the case where
                // somebody would say the message never arrived.
                true
            )
            if (result.isSuccess) {
                Timber.i("signal reaction: delivered ts=%d", timestamp)
                Result.Sent(timestamp)
            } else {
                Result.Failed(describe(result))
            }
        } catch (t: Throwable) {
            Timber.w(t, "signal reaction: send threw")
            Result.Failed(t.message ?: t::class.java.simpleName)
        }
    }

    /** The same, to a group: every member hears it, as they do a message. */
    fun sendReactionToGroup(
        masterKey: ByteArray,
        members: List<ServiceId>,
        emoji: String,
        remove: Boolean,
        targetAuthor: ServiceId,
        targetSentTimestamp: Long
    ): Result {
        if (members.isEmpty()) return Result.Failed("the group has no members this device can reach")
        val timestamp = System.currentTimeMillis()

        val group = org.whispersystems.signalservice.api.messages.SignalServiceGroupV2
            .newBuilder(org.signal.libsignal.zkgroup.groups.GroupMasterKey(masterKey))
            .withRevision(0)
            .build()

        val message = SignalServiceDataMessage.newBuilder()
            .withTimestamp(timestamp)
            .asGroupMessage(group)
            .withReaction(
                SignalServiceDataMessage.Reaction(emoji, remove, targetAuthor, targetSentTimestamp)
            )
            .build()

        return try {
            val results = sender.sendDataMessage(
                members.map { SignalServiceAddress(it) },
                members.map { sealedSender.accessFor(it.toString()) },
                false,
                ContentHint.RESENDABLE,
                message,
                SignalServiceMessageSender.LegacyGroupEvents.EMPTY,
                null,
                null,
                // urgent. The server uses this to decide whether to wake a dozing
                // recipient with a high-priority push. Sent non-urgent, an ordinary
                // message waits until their phone next connects on its own schedule --
                // which, on a phone built to stay asleep, is exactly the case where
                // somebody would say the message never arrived.
                true
            )
            val failed = results.filterNot { it.isSuccess }
            if (failed.isEmpty()) Result.Sent(timestamp)
            else Result.Failed("could not reach ${failed.size} of ${results.size} group members")
        } catch (t: Throwable) {
            Timber.w(t, "signal reaction: group send threw")
            Result.Failed(t.message ?: t::class.java.simpleName)
        }
    }

    fun send(
        recipient: ServiceId,
        body: String,
        attachments: List<String> = emptyList(),
        expiresInSeconds: Int = 0,
        expireTimerVersion: Int = 0
    ): Result {
        val timestamp = System.currentTimeMillis()
        val streams = try {
            attachments.mapNotNull { attachmentStream(it) }
        } catch (t: Throwable) {
            // Before the message is sent, not after. A message that goes out without the
            // picture someone attached is worse than one that does not go out at all: the
            // sender believes the picture was delivered.
            Timber.w(t, "signal send: could not prepare an attachment")
            return Result.Failed("could not prepare the attachment: ${t.message}")
        }
        val message = SignalServiceDataMessage.newBuilder()
            .withBody(body)
            .withTimestamp(timestamp)
            .apply { if (streams.isNotEmpty()) withAttachments(streams) }
            // The conversation's timer, re-asserted on every message the way Signal does.
            // Omitting it does not leave the timer alone: a data message with no expireTimer
            // reads as zero, so every reply this phone sent was telling the other person's
            // client that the disappearing conversation they had chosen was now off.
            //
            // The version goes with it. Signal resolves competing timer changes by version,
            // so a message carrying a timer and no version reads as older than whatever the
            // peer holds and is ignored.
            .withExpiration(expiresInSeconds)
            .withExpireTimerVersion(expireTimerVersion)
            .build()

        return try {
            val result: SendMessageResult = sender.sendDataMessage(
                SignalServiceAddress(recipient),
                // Sealed sender when we can, identified when we cannot. Null here is not a
                // decision to leak: it means this person has not shared their profile with us,
                // or the certificate could not be fetched, and Signal's own clients fall back
                // the same way. Refusing to send instead would trade a metadata leak for a
                // message that never arrives.
                sealedSender.accessFor(recipient.toString()),
                ContentHint.RESENDABLE,
                message,
                SignalServiceMessageSender.IndividualSendEvents.EMPTY,
                false,
                // urgent. The server uses this to decide whether to wake a dozing
                // recipient with a high-priority push. Sent non-urgent, an ordinary
                // message waits until their phone next connects on its own schedule --
                // which, on a phone built to stay asleep, is exactly the case where
                // somebody would say the message never arrived.
                true
            )
            if (result.isSuccess) {
                Timber.i("signal send: delivered ts=%d", timestamp)
                Result.Sent(timestamp)
            } else {
                Result.Failed(describe(result))
            }
        } catch (t: Throwable) {
            Timber.w(t, "signal send: threw")
            Result.Failed(t.message ?: t::class.java.simpleName)
        }
    }

    /**
     * Turns the app's `data:` URI into something the sender can upload.
     *
     * The composer hands attachments across as data URIs -- that is what the bridge accepted,
     * and changing it would mean touching the picker, the preview and the scheduling path for
     * no gain here.
     *
     * A CDN slot is reserved **before** the send. The sender needs somewhere to put the bytes
     * and will not go and get one itself; without a spec it falls back to a path that has no
     * upload location at all.
     */
    private fun attachmentStream(dataUri: String): SignalServiceAttachmentStream? {
        val comma = dataUri.indexOf(',')
        if (!dataUri.startsWith("data:") || comma < 0) {
            throw IllegalArgumentException("attachment is not a data URI")
        }
        val header = dataUri.substring("data:".length, comma)
        val contentType = header.substringBefore(';').ifBlank { "application/octet-stream" }
        val bytes = android.util.Base64.decode(dataUri.substring(comma + 1), android.util.Base64.DEFAULT)
        if (bytes.isEmpty()) return null

        // The reserved size is the *ciphertext* length, not the file's. Reserving the
        // plaintext size leaves the upload short of room by the padding and MAC.
        val spec = connection.cdn.getResumableUploadSpecBlocking(
            org.whispersystems.signalservice.api.crypto.AttachmentCipherStreamUtil
                .getCiphertextLength(bytes.size.toLong())
        )

        return org.whispersystems.signalservice.api.messages.SignalServiceAttachment.newStreamBuilder()
            .withStream(java.io.ByteArrayInputStream(bytes))
            .withContentType(contentType)
            .withLength(bytes.size.toLong())
            .withUploadTimestamp(System.currentTimeMillis())
            .withResumableUploadSpec(spec)
            .build()
    }

    companion object {
        /** Signal's primary device, which always has a session if any do. */
        private const val DEFAULT_DEVICE_ID = 1

        /** signal-cli's values. 0 means the server's own limit applies. */
        private const val MAX_ENVELOPE_SIZE = 0L
        private const val MAX_INCREMENTAL_MACS_PER_ENVELOPE = 10
    }
}
