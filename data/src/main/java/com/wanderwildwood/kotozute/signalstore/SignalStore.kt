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
    internal fun connection(userAgent: String) = SignalConnection(account, userAgent)

    /**
     * Publishes a batch of one-time pre keys for both identities.
     *
     * Not part of linking, because the registration request carries only a signed and a
     * last-resort key. Until this has run the device is reachable but on the degraded path,
     * every new session reusing the last-resort key.
     */
    fun uploadPreKeys(userAgent: String): String {
        val connection = connection(userAgent)
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
            connection.disconnect()
        }
    }

    /**
     * Connects, drains whatever the server is holding, and decrypts it.
     *
     * The receive half of what the bridge used to do, end to end.
     */
    fun receive(
        userAgent: String,
        certificateValidator: org.signal.libsignal.metadata.certificate.CertificateValidator,
        file: (List<com.wanderwildwood.kotozute.signal.BridgeMessage>) -> Int
    ): String {
        val connection = connection(userAgent)
        connection.connect()
        return try {
            val result = SignalReceiver(
                database, account, SignalDataStore(database, account), connection,
                certificateValidator, file
            ).drain()
            "envelopes=${result.envelopes} decrypted=${result.decrypted} failed=${result.failed} " +
                "stored=${result.stored} queue-emptied=${result.queueEmptied} senders=${result.senders.size}"
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Sends one message, on this device's own authority. The primary is not in the path.
     */
    fun send(
        userAgent: String,
        configuration: org.signal.network.config.SignalServiceConfiguration,
        recipient: String,
        body: String
    ): String {
        val serviceId = org.signal.core.models.ServiceId.parseOrNull(recipient)
            ?: return "not a service id: $recipient"
        val connection = connection(userAgent)
        connection.connect()
        return try {
            when (val result = SignalSender(
                configuration, userAgent, account, database, SignalDataStore(database, account), connection
            ).send(serviceId, body)) {
                is SignalSender.Result.Sent -> "sent ts=${result.timestamp}"
                is SignalSender.Result.Failed -> result.reason
            }
        } finally {
            connection.disconnect()
        }
    }

    fun linker(
        configuration: org.signal.network.config.SignalServiceConfiguration,
        userAgent: String
    ): DeviceLinker = DeviceLinker(
        configuration,
        userAgent,
        account,
        { SignalSignedPreKeyStore(database, it) },
        { SignalKyberPreKeyStore(database, it) }
    )
}
