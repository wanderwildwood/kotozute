package com.wanderwildwood.kotozute.signalstore

import android.content.Context
import org.whispersystems.signalservice.api.SignalServiceDataStore
import timber.log.Timber
import org.whispersystems.signalservice.api.messages.multidevice.BlockedListMessage

/**
 * The protocol store, assembled.
 *
 * One place that knows how the database is keyed and how the stores are wired, so that
 * everything above holds a [SignalStore] rather than a `SQLiteOpenHelper` and a passphrase.
 *
 * Opened lazily. Constructing this must not touch the keystore or the disk, because it is
 * built during startup on the main thread and the first open creates the database.
 */
class SignalStore(private val context: Context) {

    private val database: ProtocolDatabase by lazy {
        ProtocolDatabase(context, ProtocolStoreKey.require(context))
    }

    /** The account: who this device is, and the counters that hand out key ids. */
    internal val account: SignalAccountStore by lazy { SignalAccountStore(database) }

    /**
     * The ACI/PNI pair the service layer is constructed with.
     *
     * Typed as the library's interface rather than the implementation: everything above this
     * module consumes it through the service layer, and nothing up there should be able to
     * reach a table.
     */
    val protocol: SignalServiceDataStore by lazy { SignalDataStore(database, account) }

    /** True once this device has a device id and a password -- that is, once it is linked. */
    /** This device's own ACI, or null when it is not linked. Cheap enough to ask per thread. */
    /** Envelopes kept because they would not decrypt. See [SignalReceiver]. */
    /**
     * Why the kept envelopes would not decrypt, most recent first.
     *
     * Distinct reasons only: ten copies of the same failure is one fact, and a screen that
     * repeats it ten times buries anything else.
     */
    fun undecryptableReasons(limit: Int = 3): List<String> = runCatching {
        withStoreLock(database) {
            database.readableDatabase.rawQuery(
                "SELECT DISTINCT failure FROM envelope WHERE failure IS NOT NULL ORDER BY _id DESC LIMIT ?",
                arrayOf(limit.toString())
            ).use { c ->
                generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
            }
        }
    }.getOrDefault(emptyList())

    fun undecryptableCount(): Int = withStoreLock(database) {
        database.readableDatabase.rawQuery("SELECT count(*) FROM envelope", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun selfAciOrNull(): String? = runCatching { account.credentials().aci }.getOrNull()

    /** Which device on the account this phone is. */
    fun deviceId(): Int = runCatching { account.credentials().deviceId }.getOrDefault(0)

    /** This account's own number, for the messages an export attributes to it. */
    fun selfNumberOrNull(): String? = runCatching { account.credentials().e164 }.getOrNull()

    fun isLinked(): Boolean = ProtocolStoreKey.exists(context) && account.credentials().complete

    /**
     * The configuration is passed in rather than reached for. `SignalNetworkConfig` still
     * lives in the presentation module -- only because the smoke test that first needed it
     * did -- and this module cannot depend on that one. It belongs down here eventually,
     * along with the trust store it reads off the classpath; that is a move on its own, not
     * something to fold into a linking change.
     */
    /**
     * The connection to Signal, and the APIs on it. Built from [userAgent] because the
     * network configuration still lives a module up; see the note on [linker].
     */
    /**
     * **One** connection for the whole process, shared by everything that needs the socket.
     *
     * Not one per operation, which is what this was. Signal allows a single authenticated
     * websocket per device, so opening a second one does not add a connection -- it displaces
     * the first. The symptom was a send knocking the receive loop off the air: the listen loop
     * failed with `Connection closed!` at the exact moment a message was sent, and then
     * reconnected on its backoff, so messages arrived late rather than not at all and the
     * cause looked like a flaky network.
     */
    /**
     * Whoever holds this may read the socket. Nobody else may.
     *
     * A flag was not enough. Two readers arose from the ordering between `startStream()` and a
     * `syncNow()` that arrived in the same millisecond, and every attempt to guard it with a
     * boolean lost the race somewhere else -- because a boolean tested and then acted on is
     * two operations. This is one.
     *
     * Two threads calling readMessageBatch on one connection race for each message and the
     * loser's read fails; the library's own source warns about it. The visible symptom was a
     * reconnect every sixty seconds with healthy keepalives either side, which reads as a
     * flaky network and is not one.
     */
    private val socketReader = java.util.concurrent.locks.ReentrantLock()

    internal val connection: SignalConnection by lazy {
        SignalConnection(account, SignalNetworkConfig.USER_AGENT)
    }

    /**
     * Publishes a batch of one-time pre keys for both identities.
     *
     * Not part of linking, because the registration request carries only a signed and a
     * last-resort key. Until this has run the device is reachable but on the degraded path,
     * every new session reusing the last-resort key.
     */
    fun uploadPreKeys(): String {
        connection.connect()
        return try {
            val uploader = PreKeyUploader(
                account,
                connection,
                { SignalPreKeyStore(database, it) },
                { SignalSignedPreKeyStore(database, it) },
                { SignalKyberPreKeyStore(database, it) }
            )
            val before = uploader.serverCounts()
            val outcome = when (val result = uploader.uploadAll()) {
                is PreKeyUploader.Result.Uploaded -> "uploaded"
                is PreKeyUploader.Result.Failed -> result.reason
            }
            // Asked of the server, before and after. The upload's own 200 says the request was
            // accepted; this says the keys are there to be handed out.
            "$outcome | server before: $before | after: ${uploader.serverCounts()}"
        } finally {
            // The socket is shared and long-lived, so it is not disconnected here; closing is
            // [disconnect]. Nor is the reader lock touched: this sends and requests, it never
            // calls readMessageBatch, so it was never a reader.
            //
            // It used to unlock anyway, which throws IllegalMonitorStateException from a
            // finally -- after the network work had already succeeded. The message went out
            // and then the exception ate the code that files it, so a sent message reached
            // everybody else and never appeared in the sender's own thread.
        }
    }

    /**
     * Connects, drains whatever the server is holding, and decrypts it.
     *
     * The receive half of what the bridge used to do, end to end.
     */
    fun receive(
        events: SignalEvents,
        onNamesLearned: () -> Unit = {}
    ): String {
        // If a listen loop already has the socket there is nothing to catch up on -- it is
        // reading continuously -- and joining in would only take messages away from it.
        if (!socketReader.tryLock()) return "already listening"
        connection.connect()
        return try {
            val result = SignalReceiver(
                database, account, SignalDataStore(database, account), connection,
                SignalNetworkConfig.certificateValidator(), attachmentsFor(connection),
                contacts, blocks, keys, StoreEvents(events, onNamesLearned)
            ).drain()
            "envelopes=${result.envelopes} decrypted=${result.decrypted} failed=${result.failed} " +
                "stored=${result.stored} queue-emptied=${result.queueEmptied} senders=${result.senders.size}"
        } finally {
            // Paired with the tryLock above. This one IS a reader -- it calls drain(), which
            // calls readMessageBatch -- so it holds the lock for the duration and must give
            // it back. The socket itself stays open: it is shared, and closing is
            // [disconnect].
            socketReader.unlock()
        }
    }

    /**
     * Sends one message, on this device's own authority. The primary is not in the path.
     */
    /**
     * @return the send timestamp, which is also the message's identity.
     * @throws IllegalStateException with the reason if the send did not happen.
     *
     * A value rather than a sentence. This used to return a human-readable string that the
     * caller pulled the timestamp back out of with a regular expression -- so a change to the
     * wording would have silently stopped messages being filed, with no compiler complaint
     * and no failure at the point of the change.
     */
    /**
     * Sends to a group, fetching its membership first.
     *
     * @throws IllegalStateException naming the reason if it could not be sent.
     */
    fun sendToGroup(
        masterKey: ByteArray,
        body: String,
        expiresInSeconds: Int = 0,
        expireTimerVersion: Int = 0
    ): Long {
        connection.connect()
        val group = SignalGroups(connection, account, contacts).fetch(masterKey)
            ?: throw IllegalStateException("could not read the group's members")
        // An announcement group takes messages from its administrators only. Sending anyway
        // succeeds locally and is discarded by every recipient -- the message is lost behind a
        // tick, with nothing to tell the sender it did not arrive. Refused here instead, where
        // the refusal can be shown.
        if (group.announcementOnly && account.credentials().aci !in group.admins) {
            throw IllegalStateException(
                "Only this group's admins can post in it. Your message was not sent."
            )
        }
        val members = group.members
            .mapNotNull { org.signal.core.models.ServiceId.parseOrNull(it) }
            // Not ourselves: our own devices get the message as a sync, and encrypting to
            // our own address as though we were a peer is not the same thing.
            .filter { it.toString() != account.credentials().aci }
        return when (
            val r = SignalSender(
                SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
                SignalDataStore(database, account), connection, contacts
            ).sendToGroup(masterKey, members, body, expiresInSeconds, expireTimerVersion, group.revision)
        ) {
            is SignalSender.Result.Sent -> r.timestamp
            is SignalSender.Result.Failed -> throw IllegalStateException(r.reason)
        }
    }

    /**
     * Hangs an emoji on a message, or takes one back.
     *
     * [targetAuthor] is whoever wrote the message being reacted to -- this account when it is
     * one of ours, which is why it is asked for rather than assumed to be the other party.
     */
    fun sendReaction(
        recipient: String,
        emoji: String,
        remove: Boolean,
        targetAuthor: String,
        targetSentTimestamp: Long
    ): Long {
        val serviceId = org.signal.core.models.ServiceId.parseOrNull(recipient)
            ?: throw IllegalStateException("not a service id: $recipient")
        val author = org.signal.core.models.ServiceId.parseOrNull(targetAuthor)
            ?: throw IllegalStateException("not a service id: $targetAuthor")
        connection.connect()
        return when (
            val result = SignalSender(
                SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
                SignalDataStore(database, account), connection, contacts
            ).sendReaction(serviceId, emoji, remove, author, targetSentTimestamp)
        ) {
            is SignalSender.Result.Sent -> result.timestamp
            is SignalSender.Result.Failed -> throw IllegalStateException(result.reason)
        }
    }

    /** The same, into a group, which means every member it can reach. */
    fun sendReactionToGroup(
        masterKey: ByteArray,
        emoji: String,
        remove: Boolean,
        targetAuthor: String,
        targetSentTimestamp: Long
    ): Long {
        val author = org.signal.core.models.ServiceId.parseOrNull(targetAuthor)
            ?: throw IllegalStateException("not a service id: $targetAuthor")
        connection.connect()
        val group = SignalGroups(connection, account, contacts).fetch(masterKey)
            ?: throw IllegalStateException("could not read the group's members")
        val members = group.members
            .mapNotNull { org.signal.core.models.ServiceId.parseOrNull(it) }
            .filter { it.toString() != account.credentials().aci }
        return when (
            val result = SignalSender(
                SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
                SignalDataStore(database, account), connection, contacts
            ).sendReactionToGroup(masterKey, members, emoji, remove, author, targetSentTimestamp)
        ) {
            is SignalSender.Result.Sent -> result.timestamp
            is SignalSender.Result.Failed -> throw IllegalStateException(result.reason)
        }
    }

    /**
     * Blocks or unblocks one person, by sending the account's list back with them added or
     * taken out.
     *
     * ⚠ Refuses unless the primary has already sent this device the list. Signal's blocked
     * sync replaces rather than adds, so blocking somebody while holding no list would
     * silently unblock everyone else on it -- a failure whose only symptom is a person
     * getting through months later. [requestBlockedList] asks; this waits to be told.
     */
    fun setBlocked(aci: String, blocked: Boolean): Boolean {
        if (!blocks.known()) return false
        val serviceId = org.signal.core.models.ServiceId.parseOrNull(aci) ?: return false

        val updated = SignalBlockList.edit(
            blocks.individuals(), serviceId.toString(), blocked, System.currentTimeMillis()
        )

        connection.connect()
        val individuals = updated.mapNotNull { one ->
            val id = one.aci?.let { org.signal.core.models.ServiceId.parseOrNull(it) }
                as? org.signal.core.models.ServiceId.ACI
            if (id == null && one.e164 == null) null
            else BlockedListMessage.Individual(id, one.e164.orEmpty(), one.blockedAt)
        }
        val groupIds = blocks.groups().map { BlockedListMessage.Group(it, 0L) }

        return when (
            SignalSender(
                SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
                SignalDataStore(database, account), connection, contacts
            ).sendBlockedList(individuals, groupIds)
        ) {
            is SignalSender.Result.Sent -> {
                // Only after the account has taken it. The list held here is a copy of the
                // account's, and a copy that ran ahead of it would be a lie the next sync
                // would silently correct.
                blocks.store(updated, blocks.groups())
                true
            }
            is SignalSender.Result.Failed -> false
        }
    }

    /**
     * Tells one person that messages up to [upToTs] have been read.
     *
     * The timestamps Signal names a message by are the ones it was sent with, so the rows
     * being marked supply them; a receipt for a timestamp nobody sent means nothing.
     */
    fun sendReadReceipt(recipient: String, timestamps: List<Long>): Boolean {
        if (timestamps.isEmpty()) return true
        val serviceId = org.signal.core.models.ServiceId.parseOrNull(recipient) ?: return false
        connection.connect()
        return SignalSender(
            SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
            SignalDataStore(database, account), connection, contacts
        ).sendReadReceipt(serviceId, timestamps) is SignalSender.Result.Sent
    }

    /**
     * Tells one person that what they sent arrived on this phone.
     *
     * Called from the receive path for every message filed, not from the UI: nothing about it
     * is the reader's decision. See [SignalSender.sendDeliveryReceipt].
     */
    fun sendDeliveryReceipt(recipient: String, timestamps: List<Long>): Boolean {
        if (timestamps.isEmpty()) return true
        val serviceId = org.signal.core.models.ServiceId.parseOrNull(recipient) ?: return false
        return SignalSender(
            SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
            SignalDataStore(database, account), connection, contacts
        ).sendDeliveryReceipt(serviceId, timestamps) is SignalSender.Result.Sent
    }

    /**
     * Asks a sender to send a message again, after this phone could not read it.
     *
     * See [SignalSender.sendRetryReceipt]. Best effort: the message is already unread, and
     * failing to ask leaves it exactly as it was.
     */
    fun sendRetryReceipt(
        recipient: String,
        error: org.signal.libsignal.protocol.message.DecryptionErrorMessage,
        groupId: ByteArray?
    ): Boolean {
        val serviceId = org.signal.core.models.ServiceId.parseOrNull(recipient) ?: return false
        return SignalSender(
            SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
            SignalDataStore(database, account), connection, contacts
        ).sendRetryReceipt(serviceId, error, groupId) is SignalSender.Result.Sent
    }

    /** Whether this device has been told the blocked list yet. */
    fun blockedListKnown(): Boolean = runCatching { blocks.known() }.getOrDefault(false)

    fun isBlocked(aci: String): Boolean = runCatching { blocks.isBlocked(aci) }.getOrDefault(false)

    /**
     * Reads the account's contact list out of the storage service, where modern Signal keeps
     * it. Returns what it did, in a sentence, for a status line and a log.
     */
    fun readStorage(): String {
        val result = SignalStorageService(connection, keys, contacts) { who, key, verified ->
            // The account's own record of somebody's key, taken as this device's starting
            // point rather than trusting whatever the server offers first.
            runCatching {
                SignalDataStore(database, account).aciStore().adoptIdentity(who, key, verified)
            }.onFailure { Timber.w(it, "signal storage: could not adopt an identity") }
        }.read()
        if (result.reason != null) return result.reason
        val line = "${result.contacts} contact(s) from ${result.records} record(s)"
        // A record this could not use is said out loud. The whole of this bug was a fetch
        // that dropped two records in three and reported only the one it kept, so "71 from
        // 201" read as a complete answer rather than as the alarm it was.
        // Kept, but worth saying: a person known only by their phone-number identity can be
        // written to, and their reply still arrives under their account id, so the two halves
        // only become one conversation once the account tells this phone they are the same.
        val byPni = result.pniOnly.takeIf { it > 0 }?.let { " · $it by phone number" }.orEmpty()
        val dropped = listOfNotNull(
            // Said first, because it means the rest of the numbers are not the whole story.
            result.unreadable.takeIf { it > 0 }?.let { "$it the service would not hand over" },
            result.unopened.takeIf { it > 0 }?.let { "$it would not open" },
            result.notContacts.takeIf { it > 0 }?.let { "$it not a contact" },
            result.anonymous.takeIf { it > 0 }?.let { anon ->
                val num = result.anonymousWithNumber.takeIf { it > 0 }?.let { ", $it with a number" }
                "$anon with no address at all${num.orEmpty()}"
            }
        )
        return if (dropped.isEmpty()) "$line$byPni"
        else "$line$byPni · skipped ${dropped.joinToString(", ")}"
    }

    /**
     * Asks Signal which of [numbers] are on it, and keeps the ones that are.
     *
     * Separate from [readStorage], which reads people the account already has a record for.
     * This is the only way somebody in the phone's own address book who has never written
     * first can become writable-to.
     *
     * Says what it did in a sentence, the same as the rest of this class.
     */
    fun discover(numbers: Set<String>): String {
        val result = SignalDiscovery(connection, contacts, discovery).read(numbers)
        if (result.reason != null) return result.reason
        if (result.asked == 0) return "Every number has been looked up already"
        val parts = listOfNotNull(
            "${result.found} on Signal, from ${result.asked} number(s)",
            // Nearly all of them, for a linked device: CDSI hands back an account id only
            // where the asker already holds a matching ACI/UAK pair. Said out loud because
            // "found by phone number" is a different, weaker thing to know about somebody.
            result.withoutAci.takeIf { it > 0 }?.let { "$it known by phone number only" }
        )
        return parts.joinToString(" · ")
    }

    /** Whether the storage service key is here yet. */
    fun storageKeyKnown(): Boolean = runCatching { keys.known() }.getOrDefault(false)

    /**
     * Asks the primary for the account's keys. Asked once per connection and only while the
     * answer is still missing: it is the account's own key material, and there is no reason
     * to have it sent again once it is here.
     */
    fun requestKeys(): String {
        connection.connect()
        return when (
            val r = SignalSender(
                SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
                SignalDataStore(database, account), connection, contacts
            ).requestKeys()
        ) {
            is SignalSender.Result.Sent -> "requested"
            is SignalSender.Result.Failed -> r.reason
        }
    }

    /** Asks the primary for the blocked list. The answer arrives later, through the socket. */
    fun requestBlockedList(): String {
        connection.connect()
        return when (
            val r = SignalSender(
                SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
                SignalDataStore(database, account), connection, contacts
            ).requestBlockedList()
        ) {
            is SignalSender.Result.Sent -> "requested"
            is SignalSender.Result.Failed -> r.reason
        }
    }

    fun send(
        recipient: String,
        body: String,
        attachments: List<String> = emptyList(),
        expiresInSeconds: Int = 0,
        expireTimerVersion: Int = 0
    ): Long {
        val serviceId = org.signal.core.models.ServiceId.parseOrNull(recipient)
            ?: throw IllegalStateException("not a service id: $recipient")
        connection.connect()
        return try {
            when (val result = SignalSender(
                SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database, SignalDataStore(database, account), connection, contacts
            ).send(serviceId, body, attachments, expiresInSeconds, expireTimerVersion)) {
                is SignalSender.Result.Sent -> result.timestamp
                is SignalSender.Result.Failed -> throw IllegalStateException(result.reason)
            }
        } finally {
            // The socket is shared and long-lived, so it is not disconnected here; closing is
            // [disconnect]. Nor is the reader lock touched: this sends and requests, it never
            // calls readMessageBatch, so it was never a reader.
            //
            // It used to unlock anyway, which throws IllegalMonitorStateException from a
            // finally -- after the network work had already succeeded. The message went out
            // and then the exception ate the code that files it, so a sent message reached
            // everybody else and never appeared in the sender's own thread.
        }
    }

    /**
     * Holds the connection open and files messages as they arrive.
     *
     * Blocks for as long as [keepGoing] says to, so the caller owns the thread.
     *
     * Does **not** close the connection on the way out. The socket is shared and outlives any
     * one pass of this loop: the loop exits on every reconnect, and closing here meant each
     * retry tore down a socket the next retry immediately rebuilt. Closing is [disconnect],
     * called by whoever knows nobody wants it any more.
     */
    fun listen(
        keepGoing: () -> Boolean,
        events: SignalEvents,
        onNamesLearned: () -> Unit = {},
        onBatch: (String) -> Unit
    ) {
        socketReader.lock()
        try {
        connection.connect()
        SignalReceiver(
            database, account, SignalDataStore(database, account), connection,
            SignalNetworkConfig.certificateValidator(), attachmentsFor(connection),
            contacts, blocks, keys, StoreEvents(events, onNamesLearned)
        ).listen(keepGoing) { r ->
            onBatch("envelopes=${r.envelopes} decrypted=${r.decrypted} failed=${r.failed} stored=${r.stored}")
        }
        } finally {
            socketReader.unlock()
        }
    }

    /**
     * Bound to a connection because downloading needs one, and a connection is per-operation
     * here rather than a long-lived singleton.
     */
    /** Names for the people on the other end, from the primary's contacts sync. */
    internal val contacts: SignalContactStore by lazy { SignalContactStore(database) }

    /** The account's blocked list, as the primary last sent it. */
    internal val blocks: SignalBlockStore by lazy { SignalBlockStore(database) }

    /** The account's own keys; see [SignalKeyStore] for why they are kept where they are. */
    internal val keys: SignalKeyStore by lazy { SignalKeyStore(database) }

    /** What contact discovery has already asked about; see [SignalDiscoveryStore]. */
    internal val discovery: SignalDiscoveryStore by lazy { SignalDiscoveryStore(database) }

    /** The name known for a service id, or null. */
    /** A peer's safety number and trust level, or null if they are unknown to the store. */
    /**
     * A group's name and members, fetched from the server using the master key a message
     * carried. Null when it cannot be had -- a group whose details are unavailable should
     * still receive messages.
     */
    internal fun groupFor(masterKey: ByteArray): SignalGroups.Group? =
        runCatching { SignalGroups(connection, account, contacts).fetch(masterKey) }.getOrNull()

    internal fun identityFor(aci: String): SignalIdentityKeyStore.Identity? = runCatching {
        val self = org.signal.core.models.ServiceId.parseOrNull(account.credentials().aci)
            ?: return@runCatching null
        SignalIdentityKeyStore(database, ProtocolDatabase.ACCOUNT_ID_TYPE_ACI)
            .identityFor(aci, self)
    }.getOrNull()

    /** Accepts a peer's changed key so messages can be sent to them again. */
    fun acceptIdentity(aci: String): Boolean = runCatching {
        SignalIdentityKeyStore(database, ProtocolDatabase.ACCOUNT_ID_TYPE_ACI).acceptIdentity(aci)
    }.getOrDefault(false)

    /** "known/with-key/named", for the Connection screen. Names come from profiles. */
    fun contactSummary(): String = runCatching {
        val (known, keys, named) = contacts.counts()
        "$known contact(s), $keys with a profile key, $named named"
    }.getOrDefault("")

    fun contactName(aci: String): String? = runCatching { contacts.nameFor(aci) }.getOrNull()

    /** Every name known, for renaming threads in one pass after a sync. */
    /** The number known for one service id, or null. */
    fun contactNumber(aci: String): String? = runCatching { contacts.numberFor(aci) }.getOrNull()

    /** The service id known for a number, or null. */
    fun contactAciForNumber(e164: String): String? =
        runCatching { contacts.aciForNumber(e164) }.getOrNull()

    /** The account id a phone-number identity belongs to, where the account has said so. */
    fun contactAciForPni(pni: String): String? =
        runCatching { contacts.aciForPni(pni) }.getOrNull()

    fun contactNames(): Map<String, String> = runCatching { contacts.all() }.getOrDefault(emptyMap())

    /**
     * Everyone the primary has told us about: service id, number and name, named or not.
     * This is the whole of a linked device's directory -- there is no lookup of its own.
     */
    internal fun contactDirectory(): List<SignalContactStore.Contact> =
        runCatching { contacts.everyone() }.getOrDefault(emptyList())

    /** Asks the primary for its contacts. The answer arrives later, through the socket. */
    fun requestContacts(): String {
        connection.connect()
        return try {
            when (val r = SignalSender(
                SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
                SignalDataStore(database, account), connection, contacts
            ).requestContactsSync()) {
                is SignalSender.Result.Sent -> "requested"
                is SignalSender.Result.Failed -> r.reason
            }
        } finally {
            // The socket is shared and long-lived, so it is not disconnected here; closing is
            // [disconnect]. Nor is the reader lock touched: this sends and requests, it never
            // calls readMessageBatch, so it was never a reader.
            //
            // It used to unlock anyway, which throws IllegalMonitorStateException from a
            // finally -- after the network work had already succeeded. The message went out
            // and then the exception ate the code that files it, so a sent message reached
            // everybody else and never appeared in the sender's own thread.
        }
    }

    private fun attachmentsFor(connection: SignalConnection) =
        SignalAttachments(context) { connection.messageReceiver }

    /**
     * Files an attachment that came from an import rather than from the network, returning
     * the id to record on the message, or null if it could not be kept.
     *
     * The id becomes the filename, so it is built from the export's own name with anything
     * that is not a plain filename character removed -- the same shape the download path
     * produces, because the reader ends up in one attachment store either way.
     */
    fun keepImportedAttachment(name: String, open: () -> java.io.InputStream): String? {
        val safe = name.map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }
            .joinToString("")
            .takeIf { it.isNotBlank() && it != "." && it != ".." }
            ?: return null
        // A name that was already a plain one is kept exactly. It matters for a copy written
        // by a bridge: there the file is named by the attachment id the messages already
        // hold, and renaming it would leave every one of those rows pointing at a file that
        // is now on the phone under a name nothing asks for. A name that had to be changed
        // gets the prefix, so it cannot collide with an id that means something.
        val id = if (safe == name) name else "import-$safe"
        return id.takeIf {
            SignalAttachments(context) { error("no download needed to keep") }.keep(it, open)
        }
    }

    /** The bytes of a downloaded attachment, or null if it was never fetched. */
    fun readAttachment(id: String): ByteArray? =
        SignalAttachments(context) { error("no download needed to read") }.read(id)

    /**
     * Closes the shared socket.
     *
     * Only the thing that knows nobody wants it any more should call this -- which is
     * stopStream(), not the listen loop. The loop exits on every reconnect, and disconnecting
     * there meant each retry tore down a socket another retry was about to rebuild, so the
     * connection spent its life closing and reopening and messages arrived late.
     */
    fun disconnect() = runCatching { connection.disconnect() }.getOrNull()

    /**
     * Registering this phone as an account of its own, rather than joining one.
     *
     * Built the same way as [linker] and from the same stores, because the two flows end in
     * the same place: an identity, a set of pre keys, and credentials this device can use.
     * They differ only in how the server is persuaded to issue them.
     */
    fun registrar(): SignalRegistrar = SignalRegistrar(
        SignalNetworkConfig.production(),
        SignalNetworkConfig.USER_AGENT,
        account,
        { SignalSignedPreKeyStore(database, it) },
        { SignalKyberPreKeyStore(database, it) }
    )

    fun linker(): DeviceLinker = DeviceLinker(
        SignalNetworkConfig.production(),
        SignalNetworkConfig.USER_AGENT,
        account,
        { SignalSignedPreKeyStore(database, it) },
        { SignalKyberPreKeyStore(database, it) }
    )

    /**
     * What this store adds to whatever the caller asked for.
     *
     * Three things happen on the way through, and none of them are the caller's business: the
     * account's stored contact list is read the moment its key arrives, names are fetched
     * after a batch that made any fetchable, and receipts go back out over this device's own
     * connection. Everything else is passed straight along.
     *
     * Delegation rather than a pile of lambdas, so the next thing Signal syncs is one method
     * here instead of an argument threaded through four files. See [SignalEvents].
     */
    private inner class StoreEvents(
        private val outer: SignalEvents,
        private val onNamesLearned: () -> Unit
    ) : SignalEvents by outer {

        override fun onKeysLearned() {
            // The key has just arrived: read the account's contact list with it, and let the
            // caller rename its threads if anybody was learned.
            val read = runCatching { readStorage() }
                .onFailure { Timber.w(it, "signal storage: could not read") }
                .getOrNull()
            Timber.i("signal storage: %s", read ?: "not read")
            onNamesLearned()
            outer.onKeysLearned()
        }

        override fun afterBatch() {
            // Fetch whatever names became fetchable, then let the caller rename its threads --
            // only if something was actually learned, so a quiet batch does not walk the whole
            // thread list for nothing.
            if (SignalProfiles(connection, contacts).refreshMissingNames() > 0) onNamesLearned()
            outer.afterBatch()
        }

        override fun sendDeliveryReceipt(to: String, timestamps: List<Long>) {
            this@SignalStore.sendDeliveryReceipt(to, timestamps)
        }

        override fun sendRetryReceipt(
            to: String,
            error: org.signal.libsignal.protocol.message.DecryptionErrorMessage,
            groupId: ByteArray?
        ) {
            this@SignalStore.sendRetryReceipt(to, error, groupId)
        }
    }

}
