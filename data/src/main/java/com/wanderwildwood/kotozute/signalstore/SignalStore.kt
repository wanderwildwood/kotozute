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
            // Not disconnected: the socket is shared and long-lived. Closing is [disconnect],
            // called by whoever knows nobody wants it any more.
            socketReader.unlock()
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
            // Not disconnected: the socket is shared and long-lived. Closing is [disconnect],
            // called by whoever knows nobody wants it any more.
            socketReader.unlock()
        }
    }

    /**
     * Sends one message, on this device's own authority. The primary is not in the path.
     */
    fun send(recipient: String, body: String): String {
        val serviceId = org.signal.core.models.ServiceId.parseOrNull(recipient)
            ?: return "not a service id: $recipient"
        connection.connect()
        return try {
            when (val result = SignalSender(
                SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database, SignalDataStore(database, account), connection
            ).send(serviceId, body)) {
                is SignalSender.Result.Sent -> "sent ts=${result.timestamp}"
                is SignalSender.Result.Failed -> result.reason
            }
        } finally {
            // Not disconnected: the socket is shared and long-lived. Closing is [disconnect],
            // called by whoever knows nobody wants it any more.
            socketReader.unlock()
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
    fun contactName(aci: String): String? = runCatching { contacts.nameFor(aci) }.getOrNull()

    /** Every name known, for renaming threads in one pass after a sync. */
    fun contactNames(): Map<String, String> = runCatching { contacts.all() }.getOrDefault(emptyMap())

    /** Asks the primary for its contacts. The answer arrives later, through the socket. */
    fun requestContacts(): String {
        connection.connect()
        return try {
            when (val r = SignalSender(
                SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
                SignalDataStore(database, account), connection
            ).requestContactsSync()) {
                is SignalSender.Result.Sent -> "requested"
                is SignalSender.Result.Failed -> r.reason
            }
        } finally {
            // Not disconnected: the socket is shared and long-lived. Closing is [disconnect],
            // called by whoever knows nobody wants it any more.
            socketReader.unlock()
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

    fun linker(): DeviceLinker = DeviceLinker(
        SignalNetworkConfig.production(),
        SignalNetworkConfig.USER_AGENT,
        account,
        { SignalSignedPreKeyStore(database, it) },
        { SignalKyberPreKeyStore(database, it) }
    )
}
