package com.wanderwildwood.kotozute.signalstore

import org.signal.core.models.ServiceId
import org.signal.network.config.SignalServiceConfiguration
import org.whispersystems.signalservice.api.SignalServiceMessageSender
import org.whispersystems.signalservice.api.SignalSessionLock
import org.whispersystems.signalservice.api.crypto.ContentHint
import org.whispersystems.signalservice.api.messages.SendMessageResult
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentStream
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
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
        body: String
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
                false
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
    fun send(recipient: ServiceId, body: String, attachments: List<String> = emptyList()): Result {
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
                false
            )
            when {
                result.isSuccess -> {
                    Timber.i("signal send: delivered ts=%d", timestamp)
                    Result.Sent(timestamp)
                }
                // Worth separating. An identity failure is not a network problem: the
                // recipient's safety number changed, and retrying sends to a key we have
                // already refused to trust.
                result.identityFailure != null -> Result.Failed("identity changed for $recipient")
                result.isUnregisteredFailure -> Result.Failed("$recipient is not registered")
                result.isNetworkFailure -> Result.Failed("network failure sending to $recipient")
                result.isInvalidPreKeyFailure -> Result.Failed("$recipient has an unusable pre key")
                result.rateLimitFailure != null -> Result.Failed("rate limited")
                result.proofRequiredFailure != null -> Result.Failed("the server wants a proof of humanity")
                else -> Result.Failed("send failed for an unreported reason")
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
        /** signal-cli's values. 0 means the server's own limit applies. */
        private const val MAX_ENVELOPE_SIZE = 0L
        private const val MAX_INCREMENTAL_MACS_PER_ENVELOPE = 10
    }
}
