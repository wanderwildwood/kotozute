package com.wanderwildwood.kotozute.signalstore

import android.content.Context
import org.whispersystems.signalservice.api.SignalServiceDataStore

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
        file: (List<com.wanderwildwood.kotozute.signal.BridgeMessage>) -> Int,
        onNamesLearned: () -> Unit = {},
        receipts: (String, List<Long>, Boolean) -> Unit = { _, _, _ -> }
    ): String {
        // If a listen loop already has the socket there is nothing to catch up on -- it is
        // reading continuously -- and joining in would only take messages away from it.
        if (!socketReader.tryLock()) return "already listening"
        connection.connect()
        return try {
            val result = SignalReceiver(
                database, account, SignalDataStore(database, account), connection,
                SignalNetworkConfig.certificateValidator(), file, attachmentsFor(connection), contacts,
                {
                    // Fetch whatever names became fetchable, then let the caller rename its
                    // threads -- only if something was actually learned, so a quiet batch
                    // does not walk the whole thread list for nothing.
                    if (SignalProfiles(connection, contacts).refreshMissingNames() > 0) onNamesLearned()
                },
                receipts
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
    fun sendToGroup(masterKey: ByteArray, body: String): Long {
        connection.connect()
        val group = SignalGroups(connection, account).fetch(masterKey)
            ?: throw IllegalStateException("could not read the group's members")
        val members = group.members
            .mapNotNull { org.signal.core.models.ServiceId.parseOrNull(it) }
            // Not ourselves: our own devices get the message as a sync, and encrypting to
            // our own address as though we were a peer is not the same thing.
            .filter { it.toString() != account.credentials().aci }
        return when (
            val r = SignalSender(
                SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
                SignalDataStore(database, account), connection, contacts
            ).sendToGroup(masterKey, members, body)
        ) {
            is SignalSender.Result.Sent -> r.timestamp
            is SignalSender.Result.Failed -> throw IllegalStateException(r.reason)
        }
    }

    fun send(recipient: String, body: String, attachments: List<String> = emptyList()): Long {
        val serviceId = org.signal.core.models.ServiceId.parseOrNull(recipient)
            ?: throw IllegalStateException("not a service id: $recipient")
        connection.connect()
        return try {
            when (val result = SignalSender(
                SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database, SignalDataStore(database, account), connection, contacts
            ).send(serviceId, body, attachments)) {
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
        file: (List<com.wanderwildwood.kotozute.signal.BridgeMessage>) -> Int,
        onNamesLearned: () -> Unit,
        receipts: (String, List<Long>, Boolean) -> Unit,
        onBatch: (String) -> Unit
    ) {
        socketReader.lock()
        try {
        connection.connect()
        SignalReceiver(
            database, account, SignalDataStore(database, account), connection,
            SignalNetworkConfig.certificateValidator(), file, attachmentsFor(connection), contacts,
            {
                // Fetch whatever names became fetchable, then let the caller rename its
                // threads -- only if something was actually learned, so a quiet batch does
                // not walk the whole thread list for nothing.
                if (SignalProfiles(connection, contacts).refreshMissingNames() > 0) onNamesLearned()
            },
            receipts
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

    /** The name known for a service id, or null. */
    /** A peer's safety number and trust level, or null if they are unknown to the store. */
    /**
     * A group's name and members, fetched from the server using the master key a message
     * carried. Null when it cannot be had -- a group whose details are unavailable should
     * still receive messages.
     */
    internal fun groupFor(masterKey: ByteArray): SignalGroups.Group? =
        runCatching { SignalGroups(connection, account).fetch(masterKey) }.getOrNull()

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
}
