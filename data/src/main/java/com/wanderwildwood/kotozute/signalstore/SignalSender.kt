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
import com.wanderwildwood.kotozute.repository.SendFailure
import com.wanderwildwood.kotozute.repository.SendRefused
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

    /**
     * This account's profile key, on every message this device sends.
     *
     * Signal puts it on outgoing messages to anyone you have chosen to write to, and sending
     * somebody a message *is* that choice -- `RecipientUtil.shareProfileIfFirstSecureMessage`
     * turns sharing on at exactly this moment. Withholding it is not a private-by-default
     * posture, it is a broken conversation: without it the person on the other end cannot
     * fetch our name, so we show up as a bare service id, and cannot derive our access key, so
     * every message they send back has to name them to the server.
     */
    private val selfProfileKey: ByteArray? by lazy {
        runCatching { accounts.profileKey() }.getOrNull()
    }

    private val messageLog by lazy { SignalMessageLog(db) }

    /**
     * Throws away every session with somebody, on both identities, and our sender key with it.
     *
     * What upstream does before a second resend attempt: `archiveSessions` and
     * `archiveSiblingSessions` on the ACI store and on the PNI store when there is one, then
     * `senderKeyShared().deleteAllFor`. Archiving does not delete anything they sent us -- it
     * retires the session so the next message builds a new one.
     */
    private fun repairSessionsFor(recipient: ServiceId) {
        val name = recipient.toString()
        listOf(
            ProtocolDatabase.ACCOUNT_ID_TYPE_ACI,
            ProtocolDatabase.ACCOUNT_ID_TYPE_PNI
        ).forEach { accountIdType ->
            runCatching {
                val sessions = SignalSessionStore(db, accountIdType)
                sessions.deviceIdsFor(name).forEach { deviceId ->
                    val address =
                        org.signal.libsignal.protocol.SignalProtocolAddress(name, deviceId)
                    val record = sessions.loadSession(address)
                    record.archiveCurrentState()
                    sessions.storeSession(address, record)
                }
            }.onFailure {
                // ⚠ The sender-key record below is still dropped, deliberately. The two
                // halves repair different things -- a stale session and a stale claim that
                // they hold our group key -- and doing only the second is strictly better
                // than doing neither: the group key is re-shared, and the session they
                // cannot read is retired the next time they say so. Skipping both would
                // leave the retry having repaired nothing at all.
                Timber.w(it, "signal retry: could not archive their sessions; the next retry tries again")
            }
        }
        runCatching {
            db.writableDatabase.execSQL(
                "DELETE FROM sender_key_shared WHERE address = ?", arrayOf<Any?>(name)
            )
        }.onFailure { Timber.w(it, "signal retry: could not forget the shared sender keys") }
    }

    /**
     * A group's public identifier, derived from its master key.
     *
     * ⚠ Not the master key, which is what was being written into the log and replayed as the
     * sealed-sender group id. Both are thirty-two bytes, so the mistake could not fail loudly:
     * the resend simply carried a group id no recipient could match, so their client declined
     * to ask again and the retry loop the log exists for never closed. Worse, it put the
     * group's **master key** -- the secret the whole group is encrypted under -- into a field
     * that travels beside the message.
     *
     * Signal's group id in that argument is always `GroupSecretParams.deriveFromMasterKey(...)
     * .publicParams.groupIdentifier`, which is public by construction.
     */
    private fun groupIdentifierOf(masterKey: ByteArray?): ByteArray? = masterKey?.let {
        runCatching {
            org.signal.libsignal.zkgroup.groups.GroupSecretParams
                .deriveFromMasterKey(org.signal.libsignal.zkgroup.groups.GroupMasterKey(it))
                .publicParams
                .groupIdentifier
                .serialize()
        }.onFailure { e -> Timber.w(e, "signal send: could not derive a group identifier") }
            .getOrNull()
    }

    /**
     * Writes down what was actually sent, so a retry receipt can be answered with it.
     *
     * The Content comes back from the send itself -- it is the one that went out, not a
     * reconstruction of it. Best effort: a send that happened is still a send that happened,
     * and failing to write the log is not a reason to report it otherwise.
     */
    private fun rememberSend(
        result: SendMessageResult,
        timestamp: Long,
        groupId: ByteArray?,
        /**
         * The hint this send went out under.
         *
         * Named rather than positional, and required rather than defaulted: a default would
         * quietly record `RESENDABLE` for the one send that is not, which is the bug this
         * parameter exists to end.
         */
        contentHint: ContentHint
    ) {
        if (!result.isSuccess) return
        // What the send taught us about this person's sealed sender. Only a send that
        // actually happened is evidence, which is what the check above already ensures.
        runCatching {
            sealedSender.recordOutcome(
                result.address.serviceId.toString(),
                result.success?.isUnidentified == true
            )
        }
        val content = result.success?.content?.orElse(null) ?: return
        // One row per device the message actually reached, which is what the send result
        // names. Each of somebody's devices acknowledges separately, so each needs its own
        // copy to clear -- see [SignalMessageLog.delivered]. A result that names no device
        // still gets a row under 0 so nothing sent is unrecoverable; it simply ages out
        // rather than being cleared early.
        val devices = result.success?.devices?.takeIf { it.isNotEmpty() } ?: listOf(0)
        runCatching {
            devices.forEach { device ->
                messageLog.remember(
                    recipient = result.address.serviceId.toString(),
                    deviceId = device,
                    sentTimestamp = timestamp,
                    content = content,
                    urgent = true,
                    groupId = groupId,
                    contentHint = contentHint.type
                )
            }
        }.onFailure { Timber.w(it, "signal message log: could not record a send") }
    }

    /**
     * Sends something again, because its recipient says they could not read it.
     *
     * The other half of a retry receipt. Their client is showing nothing and waiting for this;
     * without it the message stayed lost and the promise in ContentHint.RESENDABLE was one
     * this app could not keep.
     */
    fun resend(recipient: ServiceId, sentTimestamp: Long): Result {
        val entry = runCatching { messageLog.recall(recipient.toString(), sentTimestamp) }
            .getOrNull()
            ?: return Result.Failed(SendFailure.NoLongerHeld)
        fun attempt() = sender.resendContent(
            SignalServiceAddress(recipient),
            sealedSender.accessFor(recipient.toString()),
            sentTimestamp,
            entry.content,
            // ⚠ What the original said, not a constant. The hint tells a recipient what to do
            // when they cannot read a message -- show an error now, show nothing and wait for
            // a resend, or need no error at all -- and this asserted `RESENDABLE` whatever had
            // gone out. A group update leaves as `IMPLICIT`, so a resend of one told somebody
            // to hold a slot and wait after first telling them no error was needed.
            //
            // Upstream carries it on the log entry and replays it: `ResendMessageJob` reads
            // `contentHint` off the record and passes it to `resendContent`.
            ContentHint.fromType(entry.contentHint),
            java.util.Optional.ofNullable(entry.groupId),
            entry.urgent
        )

        return try {
            val result = try {
                attempt()
            } catch (missing: org.signal.libsignal.protocol.NoSessionException) {
                // ⚠ The one failure this path should expect, and it had no answer for it.
                //
                // A retry receipt usually means the session is gone -- that is *why* they
                // could not read the message -- so resending over the same missing session
                // throws, and the retry receipt was the recipient's last resort. The message
                // is then lost for good, after RESENDABLE told their client to wait for it.
                //
                // Upstream repairs and tries once more: archive their sessions and their other
                // devices' sessions on both identities, forget that our sender key was ever
                // shared with them, resend. The second attempt builds a fresh session.
                Timber.w(missing, "signal retry: no session to send it over; repairing and trying once more")
                repairSessionsFor(recipient)
                attempt()
            }
            if (result.isSuccess) {
                Timber.i("signal retry: sent a message again for somebody who could not read it")
                Result.Sent(sentTimestamp)
            } else {
                failed(result)
            }
        } catch (t: Throwable) {
            Timber.w(t, "signal retry: could not send the message again")
            failed(t)
        }
    }

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

    /**
     * The thing that puts bytes on the wire -- and the one gate in front of all of it.
     *
     * ⚠ **Nothing leaves this device without a sealed-sender certificate.** Upstream expresses
     * that as `SealedSenderConstraint`, carried by every outgoing job it has, and a job whose
     * constraint is not met does not run: it waits, for up to a day, rather than going out
     * identified. That is a decision about privacy, not about delivery -- sealed sender is what
     * stops the server learning who is writing to whom, and quietly dropping it because a
     * certificate fetch failed hands that back.
     *
     * Here it refuses instead of waiting, because there is no job queue to wait in and a person
     * is looking at the composer. That is this app's own stated bargain too: sending fails hard,
     * because a message somebody believes they sent and which never arrives is worse than one
     * that plainly refuses. The two answers agree.
     *
     * One gate, at the lazy property every one of the eighteen send paths already goes through,
     * rather than a check at the top of each -- seventeen guards is sixteen chances to forget
     * the newest one.
     */
    private val sender: SignalServiceMessageSender by lazy {
        // ⚠ Before anything goes out, not on a maintenance pass. The refresh runs every two
        // days and should keep this from ever firing -- and the way it fires is that refresh
        // having failed quietly for a fortnight, which is exactly when nobody would otherwise
        // be told. Upstream puts its guard here too, in the send path
        // (`PushSendJob.onSend`): rotate now, and refuse rather than sign another session with
        // a key that stopped being fresh two weeks ago.
        if (!keysFreshEnoughToSend()) {
            throw SendRefused(SendFailure.KeysStale)
        }
        if (!sealedSender.available()) {
            throw SendRefused(SendFailure.NoCertificate)
        }
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
     * @return the timestamp when **anybody** got it, or a failure when nobody did.
     *
     * ⚠ This doc used to say the opposite -- that partial delivery was reported as failure --
     * and it was true once. It stopped being true when that turned out to throw the message
     * away: the caller turns a failure into an exception, which happens before the sender's own
     * copy is filed, so a message that reached nine of ten people vanished from the thread of
     * the person who wrote it while the nine sat looking at it. Retyping it delivered it twice
     * to all nine.
     *
     * Signal keeps the message and records per-recipient status. This app has no per-recipient
     * column, so it keeps the message and writes who was missed to the log, which is the part
     * a reader can act on. See the branch in the body.
     */
    fun sendToGroup(
        masterKey: ByteArray,
        members: List<ServiceId>,
        body: String,
        expiresInSeconds: Int = 0,
        expireTimerVersion: Int = 0,
        revision: Int = 0,
        quote: SignalQuote? = null,
        /** As [send]'s. */
        timestamp: Long = System.currentTimeMillis()
    ): Result {
        if (members.isEmpty()) return Result.Failed(SendFailure.NoReachableMembers)
        refuseIfTooLong(body)?.let { return it }

        val group = org.whispersystems.signalservice.api.messages.SignalServiceGroupV2
            .newBuilder(org.signal.libsignal.zkgroup.groups.GroupMasterKey(masterKey))
            // The group's real revision, not zero. Zero is never newer than a recipient's own
            // copy, so nobody refreshes group state on the strength of our message -- and a
            // recipient who has not yet learned we were added discards it as coming from
            // somebody who is not in the group.
            .withRevision(revision)
            .build()

        val message = SignalServiceDataMessage.newBuilder()
            .withBody(body)
            .withTimestamp(timestamp)
            // Kept. `PushGroupSendJob` attaches one too, gated on the *group* recipient's
            // profile sharing -- a flag that rides a GroupV2Record and that this app does not
            // hold, so there is nothing here to gate on yet. The one-to-one send below is
            // gated, which is where the leak actually was.
            .withProfileKey(selfProfileKey)
            .asGroupMessage(group)
            .withQuote(quote?.toQuote())
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
                // RESENDABLE, and now it is true. The hint tells the recipient's client "we
                // kept this and will send it again if you ask", so on a failed decrypt it
                // shows nothing and waits rather than writing an error into the thread. That
                // was a promise this app could not keep until SignalMessageLog existed; it
                // briefly said DEFAULT instead, which was honest and worse for the reader.
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
            val groupIdentifier = groupIdentifierOf(masterKey)
            results.forEach { rememberSend(it, timestamp, groupIdentifier, ContentHint.RESENDABLE) }
            results.forEach { noteIfNotRegistered(it) }
            val failed = results.filterNot { it.isSuccess }
            when {
                failed.isEmpty() -> {
                    Timber.i("signal send: delivered to %d group members ts=%d", results.size, timestamp)
                    Result.Sent(timestamp)
                }
                // ⚠ Some got it. That is a **sent** message, and calling it a failure threw it
                // away: the caller above turns Failed into an exception, which happens before
                // the sender's own copy is filed -- so a message that reached nine of ten
                // people vanished from the thread of the one person who wrote it, while the
                // nine sat looking at it. Retyping it then delivers it twice to all nine.
                //
                // Signal keeps the message and records per-recipient status. This app has no
                // per-recipient column, so it keeps the message and says who was missed, which
                // is the part a reader can act on.
                failed.size < results.size -> {
                    Timber.w(
                        "signal send: reached %d of %d group members ts=%d; missed %s",
                        results.size - failed.size, results.size, timestamp,
                        failed.joinToString { describe(it).toString() }
                    )
                    Result.Sent(timestamp)
                }
                else -> Result.Failed(SendFailure.NobodyReached(results.size))
            }
        } catch (t: Throwable) {
            Timber.w(t, "signal send: group send threw")
            failed(t)
        }
    }

    /**
     * Whether this person is still owed proof that this account's two identities are one.
     *
     * ⚠ A read that fails answers **no**. The proof is only useful to somebody who reached us
     * by number; sending it to everybody is what this replaces, and a store that will not
     * answer is not a reason to go back to that.
     */
    private fun owesPniProof(recipient: ServiceId): Boolean =
        runCatching { contacts.needsPniSignature(recipient.toString()) }
            .onFailure { Timber.w(it, "signal send: could not tell whether the proof is owed") }
            .getOrDefault(false)

    /**
     * Notes that the proof has gone, once a send carrying it has succeeded.
     *
     * Only when one was actually attached: clearing a flag for a message that did not carry
     * the proof would leave them owed it with nothing left saying so.
     */
    private fun clearPniProofIfSent(recipient: ServiceId, attached: Boolean, sent: Boolean) {
        if (!attached || !sent) return
        runCatching { contacts.clearNeedsPniSignature(recipient.toString()) }
            .onFailure {
                // Harmless in the one direction it can fail: the flag stays set, so the proof
                // is attached to one more message than it needed to be. The other way round --
                // clearing it for a message that did not carry it -- is the one this guards.
                Timber.w(it, "signal send: could not note that the proof had gone; it goes once more")
            }
    }

    /**
     * Whether the signed prekeys are fresh enough to keep sending with, replacing them if not.
     *
     * See [PreKeyUploader.refreshIfTooOldToSendWith] for the rule and where it comes from. The
     * common path is one read of the local store and no network at all.
     *
     * ⚠ A failure to *ask* is not a refusal. If this throws -- the store will not open, the
     * uploader cannot be built -- the send goes ahead: the guard exists to stop a stale key
     * being used for another fortnight, and turning "I could not check" into "you cannot send"
     * would cost somebody their message over a question nobody answered.
     */
    private fun keysFreshEnoughToSend(): Boolean = runCatching {
        PreKeyUploader(
            accounts,
            connection,
            { SignalPreKeyStore(db, it) },
            { SignalSignedPreKeyStore(db, it) },
            { SignalKyberPreKeyStore(db, it) }
        ).refreshIfTooOldToSendWith()
    }.getOrElse {
        Timber.w(it, "signal keys: could not check how old the sending keys are; sending anyway")
        true
    }

    /**
     * Refuses a message body no recipient would accept, rather than sending one that vanishes.
     *
     * ⚠ **A modern Signal client discards this message rather than showing it.**
     * `EnvelopeContentValidator` answers `Invalid("[DataMessage] Body exceeds 2048 bytes!")` for
     * anything over `SignalServiceMessageLimits.MAX_INLINE_BODY_SIZE_BYTES`, and an invalid
     * envelope is dropped on the floor. The server takes it happily, so this phone would report
     * it delivered and the person it was written to would never see it -- a message lost with a
     * tick against it, which is the worst way for one to be lost.
     *
     * ⚠ This app's receive path **did not** catch it while the service layer was the `_152`
     * jar, whose validator predated the rule: a long message reached another kotozute and was
     * discarded by that person's primary Signal -- present on one of their devices and missing
     * from another. Since the service layer is built from Signal's source, the same
     * `EnvelopeContentValidator` rule runs here too, so such a message is now dropped on the
     * way in as well. Both ends agree; neither shows it.
     *
     * Upstream splits instead: the body is trimmed to the limit and the whole text goes as a
     * `LONG_TEXT` attachment (`MessageUtil.getSplitMessage`). That is a feature this app does
     * not have, and `IndividualSendJob` keeps exactly this refusal as the backstop for when the
     * split has not happened -- `UndeliverableMessageException("The total body size was greater
     * than our limit")`. The backstop is what is ported; the split is worth having later.
     */
    private fun refuseIfTooLong(body: String): Result.Failed? =
        if (isBodyTooLong(body)) {
            Timber.w("signal send: a message body of %d bytes is over the limit; refusing", utf8Size(body))
            Result.Failed(SendFailure.TooLong)
        } else {
            null
        }

    /**
     * Tells a group's members that it has changed -- and, at creation, that it exists.
     *
     * `GroupManagerV2.createGroup` does this as its last step: the group is put on the server,
     * and then `SendGroupUpdateHelper.sendGroupUpdate` sends an update to the members. Without
     * it a new group exists on the server and on this phone, and nobody else's client has any
     * reason to ask about it -- so the group is invisible to everyone in it until somebody
     * types something.
     *
     * The message is `PushGroupSendJob`'s group-update branch, exactly: the group context with
     * its revision, **no body**, and **`ContentHint.IMPLICIT`** rather than RESENDABLE. Implicit
     * is the right hint because the message carries nothing a person wrote -- a recipient that
     * cannot read it should ask the server for the group's state, not ask us to send this
     * again.
     *
     * No profile key either. Upstream attaches one to ordinary group messages and not to this.
     *
     * The signed group change upstream can attach is not sent here: at creation it passes
     * `null` for it, and a recipient learns the change by fetching the group state the master
     * key gives them access to.
     */
    fun sendGroupUpdate(
        masterKey: ByteArray,
        members: List<ServiceId>,
        revision: Int,
        expiresInSeconds: Int = 0
    ): Result {
        if (members.isEmpty()) return Result.Failed(SendFailure.NoReachableMembers)
        val timestamp = System.currentTimeMillis()

        val group = org.whispersystems.signalservice.api.messages.SignalServiceGroupV2
            .newBuilder(org.signal.libsignal.zkgroup.groups.GroupMasterKey(masterKey))
            .withRevision(revision)
            .build()

        val message = SignalServiceDataMessage.newBuilder()
            .withTimestamp(timestamp)
            .asGroupMessage(group)
            .withExpiration(expiresInSeconds)
            .build()

        return try {
            val results = sender.sendDataMessage(
                members.map { SignalServiceAddress(it) },
                members.map { sealedSender.accessFor(it.toString()) },
                false,
                ContentHint.IMPLICIT,
                message,
                SignalServiceMessageSender.LegacyGroupEvents.EMPTY,
                null,
                null,
                // Urgent, as upstream's is: `OutgoingMessage.groupUpdateMessage` takes the
                // default and the default is true. Being added to a group is worth waking a
                // phone for -- it is the only notice the phone will get.
                true
            )
            val groupIdentifier = groupIdentifierOf(masterKey)
            results.forEach { rememberSend(it, timestamp, groupIdentifier, ContentHint.IMPLICIT) }
            results.forEach { noteIfNotRegistered(it) }
            val failed = results.filterNot { it.isSuccess }
            when {
                failed.isEmpty() -> {
                    Timber.i("signal groups: told %d member(s) about the group", results.size)
                    Result.Sent(timestamp)
                }
                // The same all/some/none shape the other group paths use: somebody heard, so
                // this is not a failure. The ones who did not will learn of the group from the
                // first message sent in it, which carries the same key and revision.
                failed.size < results.size -> {
                    Timber.w(
                        "signal groups: told %d of %d member(s); missed %s",
                        results.size - failed.size, results.size,
                        failed.joinToString { describe(it).toString() }
                    )
                    Result.Sent(timestamp)
                }
                else -> Result.Failed(SendFailure.NobodyReached(results.size))
            }
        } catch (t: Throwable) {
            Timber.w(t, "signal groups: telling the members threw")
            failed(t)
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
                Result.Failed(SendFailure.PrimaryRefusedRequest)
            }
        } catch (t: Throwable) {
            Timber.w(t, "signal contacts: request threw")
            failed(t)
        }
    }

    /**
     * Why a send did not land, in the terms that matter. Worth separating: an identity
     * failure is not a network problem -- the recipient's safety number changed, and
     * retrying sends to a key this device has already refused to trust.
     */
    sealed interface Result {
        data class Sent(val timestamp: Long) : Result

        /**
         * @param failure what went wrong, as a kind rather than a sentence: the words are the
         *   screen's to choose, in the reader's language. See [SendFailure].
         * @param safetyNumberChanged whether the send was refused because the recipient's
         *   safety number changed. Carried as a flag rather than left to [failure] alone so
         *   a caller can *offer* the decision instead of reprinting a sentence — Signal puts
         *   "Send anyway" and "Verify safety number" in front of exactly this failure, and a
         *   string nobody can match on is a string nobody can act on.
         */
        data class Failed(
            val failure: SendFailure,
            val safetyNumberChanged: Boolean = false,
            /**
             * @see SignalContactStore.markUnregistered — the service saying somebody is not on
             *   Signal is worth writing down, not just reporting.
             */
            val notRegistered: Boolean = false,
            /**
             * Whether this failed on the way to the server rather than at it -- the one kind
             * worth trying again unchanged. Upstream's `ResendMessageJob.onShouldRetry` is
             * exactly `e instanceof PushNetworkException`; a rate limit, a proof request or a
             * refusal ends the job.
             */
            val network: Boolean = false
        ) : Result
    }

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
            failed(result)
        }
    } catch (t: Throwable) {
        Timber.w(t, "signal blocked: sending the list threw")
        failed(t)
    }

    /**
     * Tells this account's other devices that its storage records have changed.
     *
     * What upstream sends after every write (`MultiDeviceStorageSyncRequestJob`):
     * without it the others find out at their next sync of their own, which on a primary with
     * nothing else to do can be a long time.
     */
    fun sendFetchLatestStorage(): Result = try {
        val result = sender.sendSyncMessage(
            SignalServiceSyncMessage.forFetchLatest(SignalServiceSyncMessage.FetchType.STORAGE_MANIFEST)
        )
        if (result.isSuccess) Result.Sent(System.currentTimeMillis()) else failed(result)
    } catch (t: Throwable) {
        Timber.w(t, "signal storage: telling the other devices threw")
        failed(t)
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
        // Returns nothing now, where it used to return a `SendMessageResult` this checked for
        // success. Nothing is lost by dropping that check: it ends in
        // `SignalServiceMessageSender.sendMessage(..., cancelationSignal = null, ...)`, whose
        // only non-throwing failure is `canceledFailure`, and that needs a cancelation signal.
        // Every other failure arrives as the exception the catch below already handles.
        sender.sendRetryReceipt(
            SignalServiceAddress(recipient),
            sealedSender.accessFor(recipient.toString()),
            java.util.Optional.ofNullable(groupId),
            error
        )
        Result.Sent(System.currentTimeMillis())
    } catch (t: Throwable) {
        Timber.w(t, "signal retry: could not ask for a message to be sent again")
        failed(t)
    }

    /**
     * Sends a message with no content, purely to rebuild a session.
     *
     * A null message carries nothing and is shown to nobody; the whole of its value is the
     * handshake around it. Sending one after archiving a broken session is what makes the far
     * end establish a fresh one, and is upstream's repair in
     * `AutomaticSessionResetJob.sendNullMessage`.
     *
     * ⚠ Chiefly for our **own primary**. A sync message that will not decrypt cannot be
     * answered with a retry receipt -- there is nobody to ask, the sender is this account --
     * so without this the ratchet stays broken and every later sync fails the same way.
     */
    fun sendNullMessage(recipient: ServiceId): Result = try {
        val result = sender.sendNullMessage(
            SignalServiceAddress(recipient),
            sealedSender.accessFor(recipient.toString())
        )
        if (result.isSuccess) {
            Result.Sent(System.currentTimeMillis())
        } else {
            failed(result)
        }
    } catch (t: Throwable) {
        Timber.w(t, "signal session: could not send a null message")
        failed(t)
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
        // ⚠ The last argument is `includePniSignature`, not `urgent` -- read from the jar's
        // `LocalVariableTable`, like the ones on the data-message send. A receipt has no
        // urgency flag at all.
        //
        // Carried here as well as on messages because a delivery receipt is often the *first*
        // thing this account sends back to somebody who wrote to its phone-number identity, so
        // it is the earliest chance to show them the two identities are one person. Upstream
        // passes it here for the same reason (`SendDeliveryReceiptJob`).
        val owedProof = owesPniProof(recipient)
        fun attempt() = sender.sendReceipt(
            SignalServiceAddress(recipient),
            sealedSender.accessFor(recipient.toString()),
            SignalServiceReceiptMessage(type, timestamps, System.currentTimeMillis()),
            owedProof
        )
        return try {
            val result = try {
                attempt()
            } catch (missing: org.signal.libsignal.protocol.NoSessionException) {
                Timber.w(missing, "signal receipt: no session for %s, archiving and retrying", what)
                archiveSessions(recipient)
                attempt()
            }
            clearPniProofIfSent(recipient, owedProof, result.isSuccess)
            if (result.isSuccess) Result.Sent(System.currentTimeMillis())
            else failed(result)
        } catch (t: Throwable) {
            Timber.w(t, "signal receipt: sending %s threw", what)
            failed(t)
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
        }.onFailure {
            // The receipt itself has already been handled; this is the tidy-up that retires a
            // session the far end has stopped using. Left alone, the next message to them
            // fails to decrypt on their side and their retry receipt brings us back here.
            Timber.w(it, "signal receipt: could not archive the stale sessions; a retry returns here")
        }
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
        if (result.isSuccess) {
            Result.Sent(System.currentTimeMillis())
        } else {
            failed(result)
        }
    } catch (t: Throwable) {
        Timber.w(t, "signal keys: requesting them threw")
        failed(t)
    }

    /**
     * Asks the primary for the account's settings.
     *
     * ⚠ A configuration sync is **volunteered only when a setting changes**. Handling one that
     * arrives -- which this app does -- is therefore not the same as knowing the account's
     * settings: a device that never asks sits on its own default until somebody happens to
     * toggle the setting in Signal. For read receipts that means a phone quietly not telling
     * people their messages were read, or telling them when the account said not to, with
     * nothing on either side to show the two disagree.
     */
    fun requestConfiguration(): Result = try {
        val result = sender.sendSyncMessage(
            SignalServiceSyncMessage.forRequest(
                RequestMessage.forType(
                    org.whispersystems.signalservice.internal.push.SyncMessage.Request.Type.CONFIGURATION
                )
            )
        )
        if (result.isSuccess) {
            Result.Sent(System.currentTimeMillis())
        } else {
            failed(result)
        }
    } catch (t: Throwable) {
        Timber.w(t, "signal configuration: requesting it threw")
        failed(t)
    }

    /**
     * Tells this account's **own** devices what has just been read here.
     *
     * Not a read receipt. A receipt goes to the person who wrote the message and is a courtesy
     * they can switch off; this goes to the account's other devices and is how a conversation
     * read on one of them stops being unread on the rest. Signal keeps them separate and
     * enqueues this one unconditionally -- `MarkReadReceiver` runs
     * `MultiDeviceReadUpdateJob.enqueue(syncMessageIds)` before it considers receipts at all,
     * and only `SendReadReceiptJob` consults the preference.
     *
     * Each entry names the message the way Signal names one everywhere: whoever wrote it, and
     * the timestamp they sent it with.
     */
    fun sendReadSync(read: List<Pair<ServiceId.ACI, Long>>): Result {
        if (read.isEmpty()) return Result.Sent(System.currentTimeMillis())
        val timestamp = System.currentTimeMillis()
        return try {
            val result = sender.sendSyncMessage(
                SignalServiceSyncMessage.forRead(
                    read.map {
                        org.whispersystems.signalservice.api.messages.multidevice.ReadMessage(
                            it.first, it.second
                        )
                    }
                )
            )
            if (result.isSuccess) {
                Timber.i("signal read sync: told our own devices about %d message(s)", read.size)
                Result.Sent(timestamp)
            } else {
                failed(result)
            }
        } catch (t: Throwable) {
            Timber.w(t, "signal read sync: send threw")
            failed(t)
        }
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
        if (result.isSuccess) {
            Result.Sent(System.currentTimeMillis())
        } else {
            failed(result)
        }
    } catch (t: Throwable) {
        Timber.w(t, "signal blocked: requesting the list threw")
        failed(t)
    }

    /**
     * Why a send did not happen, as the kind the screen words for the person who tried.
     *
     * The words are in `SignalWording`, in the presentation module, where there is a `Context`
     * to read them in the reader's language. What stays here is the part only this layer can
     * decide: which failure it was, and who it was about.
     *
     * ⚠ The identity case is the only one here the reader can *do* something about, and it was
     * the least usable: "identity changed for 4f3a...-a UUID" told them a machine fact about
     * somebody whose name this app already holds, and named no next step. Signal never shows a
     * raw address for this -- it puts a sheet in front of the send naming the person, with
     * "Send anyway" and "Verify safety number" on it.
     *
     * ⚠ This is the smaller half of that finding. Offering the decision *at the blocked send*,
     * as Signal does, is a UI change and is in the round-two queue; this at least names the
     * person and says where the decision lives.
     */
    private fun describe(result: SendMessageResult): SendFailure = when {
        result.identityFailure != null -> SendFailure.SafetyNumberChanged(whoIs(result))
        result.isUnregisteredFailure -> SendFailure.NotOnSignal(whoIs(result))
        result.isNetworkFailure -> SendFailure.Unreachable
        // Their bundle will not open. Signal does not retry this either -- asking again gets
        // the same bundle -- so it says what it is rather than suggesting another go.
        result.isInvalidPreKeyFailure -> SendFailure.KeyUnusable(whoIs(result))
        // The server usually says how long. Reporting the wait is the difference between a
        // wall and a queue; upstream backs off by exactly this value. A wait of nothing is
        // passed on as no wait, so the screen says "shortly" rather than "in 0 seconds".
        result.rateLimitFailure != null -> SendFailure.TooFast(
            result.rateLimitFailure?.retryAfterMilliseconds?.orElse(null)?.takeIf { it > 0 }
        )
        // 428. The server wants the app to prove it is a person, and this build has no way to
        // answer -- upstream opens a captcha. Said as the wall it is, rather than as jargon.
        result.proofRequiredFailure != null -> SendFailure.ProofNeeded
        else -> SendFailure.ServerSilent
    }

    /**
     * The failure form for a single-recipient send, with the one thing worth remembering done
     * on the way past. One place, so a new send path cannot forget it.
     */
    private fun failed(result: SendMessageResult): Result.Failed {
        noteIfNotRegistered(result)
        return Result.Failed(
            describe(result),
            safetyNumberChanged = result.identityFailure != null,
            notRegistered = result.isUnregisteredFailure,
            network = result.isNetworkFailure
        )
    }

    /**
     * Whether this account shares its profile with somebody, as the account's own records say.
     *
     * ⚠ **A read that fails is not a yes.** [SignalContactStore.isWhitelisted] already answers
     * `true` for a row nothing has told us about -- that is a real answer, and the right one,
     * so an existing conversation does not lose this account's name and avatar to a column
     * that arrived after it. What used to be folded in with it was the *store throwing*, which
     * is not an answer at all, and it was being read as "yes, share it".
     *
     * The two costs are not the same size. Withholding the key wrongly costs the recipient a
     * name and an avatar until the next send that can read the answer. Attaching it wrongly
     * cannot be taken back: they keep a durable key to this account's profile. So a question
     * this cannot answer is answered no, and says so.
     *
     * Upstream never has to decide this, because it does not swallow: `PushSendJob.getProfileKey`
     * reads `isSystemContact || isProfileSharing` and lets a failure propagate.
     */
    private fun sharesProfileWith(recipient: ServiceId): Boolean =
        sharesProfile { contacts.isWhitelisted(recipient.toString()) }

    /**
     * What a *thrown* failure means, the way [failed] does for a returned one.
     *
     * ⚠ The two kinds are not interchangeable, and one of them was going unread. A send to
     * several recipients collects an `UnregisteredUserException` per recipient and hands it back
     * as `SendMessageResult.unregisteredFailure`; a **one-to-one** send has nowhere to put it and
     * throws it out of `sendDataMessage` instead (`SignalServiceMessageSender:2067` rethrows it
     * rather than converting). Every catch here turned that into `explain(t)`, whose `else` arm
     * is `t.message` -- and `UnregisteredUserException(e164, cause)` is `super(cause)`, so its
     * message is the *cause's* `toString()`. The person reading a conversation was shown
     * `org.whispersystems.signalservice.api.push.exceptions.NotFoundException: ...` in place of
     * the sentence this file already had for exactly that situation.
     *
     * Worse, `notRegistered` stayed false and nothing marked the contact, so the one-to-one path
     * did not learn what the group path learns. Upstream's `IndividualSendJob:218` catches it by
     * name, fails the message and queues a `DirectoryRefreshJob`; this is that, minus the
     * directory refresh this app does not have.
     *
     * ⚠ `getE164Number()` does **not** return an e164. It is `OutgoingPushMessageList`'s
     * `destination`, built from `recipient.getIdentifier()` -- a service id. Reading it as a
     * phone number would look right and mark nobody.
     */
    private fun failed(t: Throwable): Result.Failed {
        if (t !is org.whispersystems.signalservice.api.push.exceptions.UnregisteredUserException) {
            return Result.Failed(explain(t), network = isNetwork(t))
        }
        val serviceId = t.e164Number
        runCatching { contacts.markUnregistered(serviceId) }
            .onFailure { Timber.w(it, "signal send: could not note that they have left Signal") }
        return Result.Failed(
            SendFailure.NotOnSignal(whoIs(serviceId)),
            notRegistered = true
        )
    }

    /**
     * Writes down that the service says somebody is not on Signal.
     *
     * ⚠ Called from **both** send paths. The one-to-one path had this and the group path did
     * not, which is the same "fixed one arm of the `when` and not its neighbours" that the
     * previous finding was about -- and worse here, because a group is exactly where a member
     * who has left is most likely to be found: nobody writes to them one-to-one any more,
     * which is why they went unnoticed.
     *
     * Upstream collects them per send (`GroupSendJobHelper`'s `unregistered` list) and its
     * callers mark each one; this is that, at the point both paths already inspect results.
     */
    private fun noteIfNotRegistered(result: SendMessageResult) {
        if (!result.isUnregisteredFailure) return
        runCatching { contacts.markUnregistered(result.address.serviceId.toString()) }
            .onFailure { Timber.w(it, "signal send: could not note that they have left Signal") }
    }

    /**
     * What to call the recipient of a failed send, or null where only an id is held -- the
     * screen words the sentence without a name then.
     */
    private fun whoIs(result: SendMessageResult): String? =
        whoIs(result.address.serviceId.toString())

    private fun whoIs(serviceId: String): String? =
        runCatching { contacts.nameFor(serviceId) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }



    /**
     * A reaction: an emoji hung on somebody else's message rather than a message of its own.
     *
     * Signal names the message being reacted to by who wrote it and when they sent it, so
     * [targetAuthor] is the *author of that message* -- this account when the reaction is to
     * something we sent ourselves, which is the case that reads wrong if it is guessed.
     */
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
            // ⚠ No profile key on a reaction. Upstream's `ReactionSendJob` builds its data
            // message with a timestamp and the reaction and nothing else -- a profile key
            // rides ordinary messages, not the housekeeping that follows them, which is the
            // same reason `RemoteDeleteSendJob` carries none either.
            .withReaction(
                SignalServiceDataMessage.Reaction(emoji, remove, targetAuthor, targetSentTimestamp)
            )
            .build()

        return try {
            val owedProof = owesPniProof(recipient)
            val result = sender.sendDataMessage(
                SignalServiceAddress(recipient),
                sealedSender.accessFor(recipient.toString()),
                ContentHint.RESENDABLE,
                message,
                SignalServiceMessageSender.IndividualSendEvents.EMPTY,
                // ⚠⚠ **These two were the wrong way round**, and the comment below described
                // the second one while it sat on the first. Read out of the bytecode, not
                // guessed: parameter 6 is `urgent` and parameter 7 is `includePniSignature`.
                //
                // urgent. The server uses this to decide whether to wake a dozing recipient
                // with a high-priority push. Sent non-urgent -- which every one-to-one message
                // from this phone was -- an ordinary message waits until their phone next
                // connects on its own schedule, which on a phone built to stay asleep is
                // exactly the case where somebody would say the message never arrived.
                // Upstream sends all three of these urgent (`GroupSendUtil
                // .sendResendableDataMessage`, `urgent = true`).
                true,
                // includePniSignature. Only where it is owed -- somebody who messaged this
                // account at its phone-number identity and has not yet been shown the two
                // identities are one person. It was hardcoded **true**, so every message handed
                // this account's PNI to people who only ever knew its account id, which is the
                // opposite of what the ACI-only sender certificate here is chosen to avoid.
                owedProof
            )
            clearPniProofIfSent(recipient, owedProof, result.isSuccess)
            if (result.isSuccess) {
            // ⚠ Logged, because it went out as RESENDABLE. That hint tells the recipient's
            // client to show nothing and wait for a resend if it cannot read the message --
            // so promising it while keeping no copy leaves them waiting for something that
            // can never be served. Upstream routes every resendable send through
            // `sendResendableDataMessage`, which records the payload; sends that are not meant
            // to be resent use a different path and a different hint.
                rememberSend(result, timestamp, null, ContentHint.RESENDABLE)
                Timber.i("signal reaction: delivered ts=%d", timestamp)
                Result.Sent(timestamp)
            } else {
                failed(result)
            }
        } catch (t: Throwable) {
            Timber.w(t, "signal reaction: send threw")
            failed(t)
        }
    }

    /** The same, to a group: every member hears it, as they do a message. */
    fun sendReactionToGroup(
        masterKey: ByteArray,
        members: List<ServiceId>,
        emoji: String,
        remove: Boolean,
        targetAuthor: ServiceId,
        targetSentTimestamp: Long,
        /** The group's current revision; see the note in the body. */
        revision: Int
    ): Result {
        if (members.isEmpty()) return Result.Failed(SendFailure.NoReachableMembers)
        val timestamp = System.currentTimeMillis()

        val group = org.whispersystems.signalservice.api.messages.SignalServiceGroupV2
            .newBuilder(org.signal.libsignal.zkgroup.groups.GroupMasterKey(masterKey))
            // The group's real revision, not zero -- see [sendToGroup], which has said so
            // since it was written. A message stamped 0 is never newer than a recipient's own
            // copy, so a client whose group state predates us never refreshes and discards
            // this as coming from a non-member. Upstream attaches the revision through one
            // helper for every message type, reactions and withdrawals included.
            .withRevision(revision)
            .build()

        val message = SignalServiceDataMessage.newBuilder()
            .withTimestamp(timestamp)
            // ⚠ No profile key on a reaction. Upstream's `ReactionSendJob` builds its data
            // message with a timestamp and the reaction and nothing else -- a profile key
            // rides ordinary messages, not the housekeeping that follows them, which is the
            // same reason `RemoteDeleteSendJob` carries none either.
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
            // Logged for the same reason as the one-to-one reaction above.
            val reactionGroupId = groupIdentifierOf(masterKey)
            results.forEach { rememberSend(it, timestamp, reactionGroupId, ContentHint.RESENDABLE) }
            results.forEach { noteIfNotRegistered(it) }
            val failed = results.filterNot { it.isSuccess }
            // ⚠ Some got it is **sent**, not failed. The group message path was fixed for this
            // in round one and these two were left with the old shape, so the same bug lived
            // on here: the caller turns Failed into an exception, which happens before the
            // local copy is written, so a reaction or a withdrawal that reached nine of ten
            // people vanished from the screen of the one person who did it while the nine kept
            // seeing it.
            //
            // Signal's own words for this case, in `ReactionSendJob.onFailure`: "it sent to
            // someone, so it stays." It rolls the local change back only when the send reached
            // nobody at all -- which here is the branch below, where nothing is written in the
            // first place.
            when {
                failed.isEmpty() -> Result.Sent(timestamp)
                failed.size < results.size -> {
                    Timber.w(
                        "signal send: reached %d of %d group members ts=%d; missed %s",
                        results.size - failed.size, results.size, timestamp,
                        failed.joinToString { describe(it).toString() }
                    )
                    Result.Sent(timestamp)
                }
                else -> Result.Failed(SendFailure.NobodyReached(results.size))
            }
        } catch (t: Throwable) {
            Timber.w(t, "signal reaction: group send threw")
            failed(t)
        }
    }

    /**
     * Takes a message back, for everyone who was sent it.
     *
     * Signal's own shape, from `RemoteDeleteSendJob.deliver`: an ordinary data message whose
     * only content is the sent-timestamp of the message being withdrawn. It carries **no
     * profile key** -- Signal attaches one to messages people read, not to the housekeeping
     * that follows them -- and it is sent urgent and resendable, because a withdrawal that
     * waits for the recipient's next idle connection leaves the message standing on their
     * screen in the meantime.
     *
     * A message is named here the way Signal names one everywhere: by who wrote it and the
     * timestamp it was sent with. The author is this account, so only the timestamp travels.
     */
    fun sendRemoteDelete(recipient: ServiceId, targetSentTimestamp: Long): Result {
        val timestamp = System.currentTimeMillis()
        val message = SignalServiceDataMessage.newBuilder()
            .withTimestamp(timestamp)
            .withRemoteDelete(SignalServiceDataMessage.RemoteDelete(targetSentTimestamp))
            .build()

        return try {
            val owedProof = owesPniProof(recipient)
            val result = sender.sendDataMessage(
                SignalServiceAddress(recipient),
                sealedSender.accessFor(recipient.toString()),
                ContentHint.RESENDABLE,
                message,
                SignalServiceMessageSender.IndividualSendEvents.EMPTY,
                // urgent, then includePniSignature. See the note in [send]: these were the
                // wrong way round everywhere.
                true,
                owedProof
            )
            if (result.isSuccess) {
            // ⚠ Logged, because it went out as RESENDABLE. That hint tells the recipient's
            // client to show nothing and wait for a resend if it cannot read the message --
            // so promising it while keeping no copy leaves them waiting for something that
            // can never be served. Upstream routes every resendable send through
            // `sendResendableDataMessage`, which records the payload; sends that are not meant
            // to be resent use a different path and a different hint.
                rememberSend(result, timestamp, null, ContentHint.RESENDABLE)
                clearPniProofIfSent(recipient, owedProof, true)
                Timber.i("signal delete: withdrawal sent for ts=%d", targetSentTimestamp)
                Result.Sent(timestamp)
            } else {
                failed(result)
            }
        } catch (t: Throwable) {
            Timber.w(t, "signal delete: sending the withdrawal threw")
            failed(t)
        }
    }

    /** The same, to a group: it has to reach everybody who was sent the message. */
    fun sendRemoteDeleteToGroup(
        masterKey: ByteArray,
        members: List<ServiceId>,
        targetSentTimestamp: Long,
        /** The group's current revision; see the note in the body. */
        revision: Int
    ): Result {
        if (members.isEmpty()) return Result.Failed(SendFailure.NoReachableMembers)
        val timestamp = System.currentTimeMillis()

        val group = org.whispersystems.signalservice.api.messages.SignalServiceGroupV2
            .newBuilder(org.signal.libsignal.zkgroup.groups.GroupMasterKey(masterKey))
            // The group's real revision, not zero -- see [sendToGroup], which has said so
            // since it was written. A message stamped 0 is never newer than a recipient's own
            // copy, so a client whose group state predates us never refreshes and discards
            // this as coming from a non-member. Upstream attaches the revision through one
            // helper for every message type, reactions and withdrawals included.
            .withRevision(revision)
            .build()

        val message = SignalServiceDataMessage.newBuilder()
            .withTimestamp(timestamp)
            .asGroupMessage(group)
            .withRemoteDelete(SignalServiceDataMessage.RemoteDelete(targetSentTimestamp))
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
                true
            )
            // A withdrawal that cannot be resent is a withdrawal somebody never receives.
            val deleteGroupId = groupIdentifierOf(masterKey)
            results.forEach { rememberSend(it, timestamp, deleteGroupId, ContentHint.RESENDABLE) }
            results.forEach { noteIfNotRegistered(it) }
            val failed = results.filterNot { it.isSuccess }
            // ⚠ Some got it is **sent**, not failed. The group message path was fixed for this
            // in round one and these two were left with the old shape, so the same bug lived
            // on here: the caller turns Failed into an exception, which happens before the
            // local copy is written, so a reaction or a withdrawal that reached nine of ten
            // people vanished from the screen of the one person who did it while the nine kept
            // seeing it.
            //
            // Signal's own words for this case, in `ReactionSendJob.onFailure`: "it sent to
            // someone, so it stays." It rolls the local change back only when the send reached
            // nobody at all -- which here is the branch below, where nothing is written in the
            // first place.
            when {
                failed.isEmpty() -> Result.Sent(timestamp)
                failed.size < results.size -> {
                    Timber.w(
                        "signal send: reached %d of %d group members ts=%d; missed %s",
                        results.size - failed.size, results.size, timestamp,
                        failed.joinToString { describe(it).toString() }
                    )
                    Result.Sent(timestamp)
                }
                else -> Result.Failed(SendFailure.NobodyReached(results.size))
            }
        } catch (t: Throwable) {
            Timber.w(t, "signal delete: the group withdrawal threw")
            failed(t)
        }
    }

    /**
     * Sends [body] to one recipient.
     *
     * The timestamp is the message's identity, not a decoration: it is half of the
     * `(author, timestamp)` pair every other device uses to recognise this message, including
     * our own other devices when this send comes back to them as a sync. So it is generated
     * once, here, and returned -- not read back from anything.
     */
    fun send(
        recipient: ServiceId,
        body: String,
        attachments: List<String> = emptyList(),
        expiresInSeconds: Int = 0,
        expireTimerVersion: Int = 0,
        quote: SignalQuote? = null,
        // Chosen by the caller when the message is written down before it is sent, so a resend
        // after the process dies is the same message to everyone -- a recipient that already
        // has it recognises the pair and drops the copy.
        timestamp: Long = System.currentTimeMillis()
    ): Result {
        refuseIfTooLong(body)?.let { return it }
        val streams = try {
            attachments.mapNotNull { attachmentStream(it) }
        } catch (t: Throwable) {
            // Before the message is sent, not after. A message that goes out without the
            // picture someone attached is worse than one that does not go out at all: the
            // sender believes the picture was delivered.
            Timber.w(t, "signal send: could not prepare an attachment")
            return Result.Failed(SendFailure.AttachmentUnprepared("${t.message}"))
        }
        val message = SignalServiceDataMessage.newBuilder()
            .withBody(body)
            .withTimestamp(timestamp)
            // ⚠ Only where the account shares its profile with them.
            //
            // This attached the key to every message regardless. Signal's
            // `PushSendJob.getProfileKey` returns nothing unless the recipient
            // `isSystemContact || isProfileSharing`, so somebody the account has
            // un-whitelisted -- blocked and then unblocked, or sharing turned off on another
            // device -- was handed a durable key to this account's name and avatar on the
            // next message sent to them. Unknown counts as shared; see
            // [SignalContactStore.isWhitelisted].
            .withProfileKey(selfProfileKey?.takeIf { sharesProfileWith(recipient) })
            .apply { if (streams.isNotEmpty()) withAttachments(streams) }
            .withQuote(quote?.toQuote())
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

        // ⛔ **A note to self is not a message to a recipient, it is a sync transcript.**
        //
        // Sent the ordinary way it goes to the server addressed to this very device, and the
        // server refuses it: `[403] Authorization failed`, which reads as broken credentials
        // and is nothing of the kind. Signal never sends one that way --
        // `SignalServiceMessageSender.sendSyncMessage(SignalServiceDataMessage)` wraps it as a
        // self-send transcript, and short-circuits to success without touching the network
        // when the account has no other devices to tell.
        //
        // ⚠ Unseen until now because it takes an account with exactly one device, which is
        // what **registering** produces and what **linking** never can. A phone that had
        // registered its own account could not write to itself at all.
        //
        // The local copy is filed by the caller either way, so nothing is lost when this
        // sends nothing: with no other devices there is genuinely nobody to tell.
        if (isSelf(recipient)) return sendToSelf(message, timestamp)

        return try {
            val owedProof = owesPniProof(recipient)
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
                // ⚠⚠ **These two were the wrong way round**, and the comment below described
                // the second one while it sat on the first. Read out of the bytecode, not
                // guessed: parameter 6 is `urgent` and parameter 7 is `includePniSignature`.
                //
                // urgent. The server uses this to decide whether to wake a dozing recipient
                // with a high-priority push. Sent non-urgent -- which every one-to-one message
                // from this phone was -- an ordinary message waits until their phone next
                // connects on its own schedule, which on a phone built to stay asleep is
                // exactly the case where somebody would say the message never arrived.
                // Upstream sends all three of these urgent (`GroupSendUtil
                // .sendResendableDataMessage`, `urgent = true`).
                true,
                // includePniSignature. Only where it is owed -- somebody who messaged this
                // account at its phone-number identity and has not yet been shown the two
                // identities are one person. It was hardcoded **true**, so every message handed
                // this account's PNI to people who only ever knew its account id, which is the
                // opposite of what the ACI-only sender certificate here is chosen to avoid.
                owedProof
            )
            rememberSend(result, timestamp, null, ContentHint.RESENDABLE)
            clearPniProofIfSent(recipient, owedProof, result.isSuccess)
            if (result.isSuccess) {
                Timber.i("signal send: delivered ts=%d", timestamp)
                Result.Sent(timestamp)
            } else {
                failed(result)
            }
        } catch (t: Throwable) {
            Timber.w(t, "signal send: threw")
            failed(t)
        }
    }

    /**
     * Whether [recipient] is this account itself.
     *
     * ⚠ **Both identities, not just the ACI.** An account has two -- the account id and the
     * phone-number identity -- and which one a thread carries depends on how the person was
     * found. Contact discovery resolves a number it cannot match to an account as a **PNI**
     * (`found 1, 1 of them by phone-number identity`), so a Note to Self started from the
     * address book addresses our own PNI, not our own ACI. Checking only the ACI missed it
     * and the message went to the server as an ordinary send, which is the failure this whole
     * guard exists to prevent.
     */
    private fun isSelf(recipient: ServiceId): Boolean = runCatching {
        val credentials = accounts.credentials()
        val aci = ServiceId.ACI.parseOrNull(credentials.aci)
        val pni = ServiceId.PNI.parseOrNull(credentials.pni)
        (aci != null && recipient == aci) || (pni != null && recipient == pni)
    }.getOrDefault(false)

    /**
     * Files a note to self, as a sync transcript to whatever other devices exist.
     *
     * With no other devices the library sends nothing and reports success, which is the right
     * outcome rather than a silent failure: the message is already stored here, and a
     * transcript exists only to tell other devices what this one did.
     */
    private fun sendToSelf(message: SignalServiceDataMessage, timestamp: Long): Result = try {
        val result = sender.sendSyncMessage(message)
        if (result.isSuccess) {
            Timber.i("signal send: note to self filed ts=%d", timestamp)
            Result.Sent(timestamp)
        } else {
            failed(result)
        }
    } catch (t: Throwable) {
        Timber.w(t, "signal send: note to self threw")
        failed(t)
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

        // The reserved size is the ciphertext length **of the padded plaintext**, which is
        // what actually goes up.
        //
        // ⚠ This reserved the ciphertext length of the *unpadded* bytes. Signal pads every
        // attachment before encrypting it -- the size of a file says something about it, and
        // padding is what stops that -- so the stream the library uploads is bigger than the
        // slot that had been booked, by about five per cent, for essentially every picture.
        // Upstream never writes one without the other: `getCiphertextLength(PaddingInput
        // Stream.getPaddedSize(size))` appears at every call site that reserves a slot.
        val padded = org.whispersystems.signalservice.internal.crypto.PaddingInputStream
            .getPaddedSize(bytes.size.toLong())
        val spec = connection.cdn.getResumableUploadSpecBlocking(
            org.whispersystems.signalservice.api.crypto.AttachmentCipherStreamUtil
                .getCiphertextLength(padded)
        )

        return org.whispersystems.signalservice.api.messages.SignalServiceAttachment.newStreamBuilder()
            .withStream(java.io.ByteArrayInputStream(bytes))
            .withContentType(contentType)
            .withLength(bytes.size.toLong())
            .withUploadTimestamp(System.currentTimeMillis())
            .withResumableUploadSpec(spec)
            // ⚠ The flag is what makes a recording a voice note rather than a file. Without
            // it the bytes arrive intact and every other Signal client offers to download
            // them instead of to play them -- the attachment is not broken, it is just not
            // presented as something somebody said. See [VoiceNotes] for why the marker
            // rides inside the data URI rather than beside it.
            .withVoiceNote(com.wanderwildwood.kotozute.signal.VoiceNotes.isMarked(dataUri))
            .build()
    }

    companion object {

        /**
         * The rule on its own, so it can be tested past the case that matters.
         *
         * A guard that has never been seen to refuse is not evidence of anything, and the
         * refusal here only happens when a database read throws -- which does not happen on a
         * healthy account, which is exactly why it went unnoticed.
         */
        internal fun sharesProfile(read: () -> Boolean): Boolean =
            runCatching(read)
                .onFailure {
                    Timber.w(
                        it,
                        "signal send: could not read whether this account shares its profile; " +
                            "withholding the key"
                    )
                }
                .getOrDefault(false)

        /** Signal's primary device, which always has a session if any do. */
        private const val DEFAULT_DEVICE_ID = 1

        /**
         * 256 KiB, which is Signal's `android.maxEnvelopeSizeBytes` default.
         *
         * ⚠ This was 0, taken from signal-cli, with a comment saying that meant "the server's
         * own limit applies". The library reads it as `maxEnvelopeSize > 0`, so zero does not
         * defer to anything -- it **turns the check off**. An oversized message then goes to
         * the server and comes back as an opaque rejection, where Signal refuses it here with
         * a ContentTooLargeException that says what made it large.
         */
        private const val MAX_ENVELOPE_SIZE = 256L * 1024L

        /**
         * The most a message body may be, in bytes of UTF-8.
         *
         * Upstream's own constant, referenced rather than copied. It was written out as `2 *
         * 1024` while the service layer was the `_152` jar, which predated the class that
         * holds it -- with the layer built from Signal's source there is nothing left to copy,
         * and the number can no longer drift from the one the receive-side validator enforces.
         */
        internal val MAX_INLINE_BODY_SIZE_BYTES: Int =
            org.whispersystems.signalservice.api.messages.SignalServiceMessageLimits.MAX_INLINE_BODY_SIZE_BYTES

        /**
         * What a thrown failure means for somebody whose send it was.
         *
         * ⚠ **Every catch here said `t.message ?: t::class.java.simpleName`**, so the service's
         * four most consequential refusals reached the screen as the word
         * "ProofRequiredException" or as nothing at all. Each of them is a different situation
         * with a different thing to do about it, and none of them is "try again", which is what
         * a bare failure invites.
         *
         * The one that matters most is `ProofRequiredException` -- a 428, the server asking the
         * *account* to prove it is a person before it will take more messages. It cannot be
         * answered from here: upstream's `ProofRequiredExceptionHandler` either solves a push
         * challenge over FCM, which this phone has no part in, or raises a captcha, which is a
         * whole screen this app does not have. So what is ported is the sentence, not the
         * handler -- and its wording names where the challenge *can* be answered rather than
         * pretending this phone can do it.
         *
         * A kind, not a sentence: `SignalWording` in the presentation module words it, in the
         * reader's language. The unlinked and deprecated wordings deliberately match
         * [SignalSocketHealthMonitor]'s, because the socket already says these two and hearing
         * the same thing in two different ways about one situation is worse than hearing it
         * twice.
         */
        internal fun explain(t: Throwable): SendFailure = when (t) {
            // Already decided, by the gate in front of the sender; see [sender].
            is SendRefused -> t.failure

            is org.whispersystems.signalservice.api.push.exceptions.ProofRequiredException ->
                SendFailure.ProofRequired(t.retryAfterSeconds)

            is org.whispersystems.signalservice.api.push.exceptions.RateLimitException ->
                SendFailure.RateLimited(t.retryAfterMilliseconds.orElse(0L) / 1000)

            is org.whispersystems.signalservice.api.push.exceptions.AuthorizationFailedException ->
                SendFailure.Unlinked

            is org.whispersystems.signalservice.api.push.exceptions.DeprecatedVersionException ->
                SendFailure.VersionRefused

            is org.whispersystems.signalservice.api.push.exceptions.ServerRejectedException ->
                SendFailure.ServerRejected

            // ⚠ Unnamed here, because this is the companion and has no contact store to ask.
            // [failed] handles it first and says who; this arm exists so that a catch added
            // later, calling `explain` directly the way every catch here once did, still cannot
            // put `NotFoundException` in front of a person. `UnregisteredUserException` is
            // `super(cause)`, so its own `message` is the cause's `toString()`.
            is org.whispersystems.signalservice.api.push.exceptions.UnregisteredUserException ->
                SendFailure.TheyLeft

            else -> SendFailure.Unexplained(t.message ?: t::class.java.simpleName)
        }

        /**
         * Whether a failure never reached the server: a [PushNetworkException] anywhere in its
         * causes. Its own test, not a [SendFailure] arm, because [explain] words it as the
         * exception's own message -- and the one thing a retry needs to know is this.
         *
         * Disjoint from the refusals by construction: `RateLimitException`,
         * `ProofRequiredException` and `ServerRejectedException` all extend
         * `NonSuccessfulResponseCodeException`, which is not a `PushNetworkException`.
         */
        internal fun isNetwork(t: Throwable): Boolean =
            generateSequence(t) { it.cause }
                .take(8)
                .any { it is org.signal.network.exceptions.PushNetworkException }

        /** A body's length as the limit counts it: bytes of UTF-8, not characters. */
        internal fun utf8Size(body: String): Int = body.toByteArray(Charsets.UTF_8).size

        /**
         * Whether a body is longer than any recipient will accept.
         *
         * Its own function so the boundary can be tested, and because "how long is this" has a
         * wrong answer that looks right: `String.length` counts UTF-16 units, so an emoji or a
         * kana costs more than it appears to and a message that passes a character check can
         * still be refused by the far end.
         */
        internal fun isBodyTooLong(body: String): Boolean =
            utf8Size(body) > MAX_INLINE_BODY_SIZE_BYTES
        private const val MAX_INCREMENTAL_MACS_PER_ENVELOPE = 10
    }
}
