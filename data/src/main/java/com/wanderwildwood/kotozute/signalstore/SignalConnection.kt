package com.wanderwildwood.kotozute.signalstore

import org.signal.core.models.ServiceId
import org.signal.core.util.UptimeSleepTimer
import org.signal.libsignal.net.Network
import org.whispersystems.signalservice.api.keys.KeysApi
import org.whispersystems.signalservice.api.util.CredentialsProvider
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.internal.websocket.LibSignalChatConnection
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * The account's connection to Signal: the authenticated websocket, and the APIs that ride it.
 *
 * This is the piece the bridge used to be. Everything the app previously asked a computer on
 * the LAN to do -- fetch messages, upload keys, send -- goes through here instead.
 *
 * Two sockets, not one, and the distinction is not incidental. The **authenticated** one
 * carries this device's credentials and is how messages addressed to us arrive. The
 * **unauthenticated** one carries none, and exists so that sealed-sender traffic is not tied
 * to our identity by the mere fact of the connection it arrived on. Collapsing them would
 * hand the server exactly the metadata sealed sender is designed to withhold.
 */
internal class SignalConnection(
    private val accounts: SignalAccountStore,
    private val userAgent: String,
    private val configuration: org.signal.network.config.SignalServiceConfiguration =
        SignalNetworkConfig.production()
) {

    /**
     * Built from the store on every call rather than captured once.
     *
     * The device id and password are written at linking, and anything constructed before that
     * would authenticate as device 0 with no password for the life of the process -- which
     * fails in a way that looks like a rejected account rather than a stale object.
     */
    private val credentials = object : CredentialsProvider {
        override fun getAci(): ServiceId.ACI? = ServiceId.ACI.parseOrNull(accounts.credentials().aci)
        override fun getPni(): ServiceId.PNI? = ServiceId.PNI.parseOrNull(accounts.credentials().pni)
        override fun getE164(): String? = accounts.credentials().e164
        override fun getDeviceId(): Int = accounts.credentials().deviceId
        override fun getPassword(): String? = accounts.credentials().password
    }

    /**
     * libsignal's own network handle.
     *
     * Not private: contact discovery runs inside libsignal's enclave client rather than over
     * the websocket, so it needs this as well as [authenticated]. Everything else here is
     * reached through one of the APIs below.
     */
    val network by lazy {
        Network(Network.Environment.PRODUCTION, userAgent, emptyMap(), Network.BuildVariant.PRODUCTION)
    }

    /**
     * The REST half of the service, for the few things that are not messages: the storage
     * service, which keeps the account's contact list, is reached this way rather than
     * through the socket.
     */
    val push: org.whispersystems.signalservice.internal.push.PushServiceSocket by lazy {
        org.whispersystems.signalservice.internal.push.PushServiceSocket(
            configuration, credentials, userAgent, true
        )
    }

    val authenticated: SignalWebSocket.AuthenticatedWebSocket by lazy {
        val timer = UptimeSleepTimer()
        val monitor = SignalSocketHealthMonitor(timer)
        SignalWebSocket.AuthenticatedWebSocket(
            { LibSignalChatConnection("normal", network, credentials, ALLOW_STORIES, monitor) },
            { true },
            timer,
            DISCONNECT_TIMEOUT_MS
        ).also(monitor::monitor)
    }

    val unauthenticated: SignalWebSocket.UnauthenticatedWebSocket by lazy {
        val timer = UptimeSleepTimer()
        val monitor = SignalSocketHealthMonitor(timer)
        SignalWebSocket.UnauthenticatedWebSocket(
            { LibSignalChatConnection("unidentified", network, null, ALLOW_STORIES, monitor) },
            { true },
            timer,
            DISCONNECT_TIMEOUT_MS
        ).also(monitor::monitor)
    }

    val keys: KeysApi by lazy { KeysApi(authenticated, unauthenticated) }

    /** Group operations need the zk parameters as well as the socket. */
    val groups: org.whispersystems.signalservice.api.groupsv2.GroupsV2Api by lazy {
        org.whispersystems.signalservice.api.groupsv2.GroupsV2Api(
            authenticated,
            org.whispersystems.signalservice.internal.push.PushServiceSocket(
                configuration, credentials, userAgent, true
            ),
            org.whispersystems.signalservice.api.groupsv2.GroupsV2Operations(
                org.whispersystems.signalservice.api.groupsv2.ClientZkOperations.create(configuration),
                GROUP_MAX_SIZE
            )
        )
    }

    /** Sender certificates, for sealed sender. */
    val certificates: org.signal.network.api.CertificateApi by lazy {
        org.signal.network.api.CertificateApi(authenticated)
    }

    /** Reused by the sender: uploading an attachment needs a slot on the CDN first. */
    val restClient: org.signal.network.rest.SignalRestClient by lazy {
        org.signal.network.rest.SignalRestClient(configuration, userAgent, credentials)
    }

    val cdn: org.signal.network.service.CdnService by lazy {
        org.signal.network.service.CdnService(
            restClient,
            org.signal.network.api.AttachmentApi(
                authenticated,
                org.whispersystems.signalservice.internal.push.PushServiceSocket(
                    configuration, credentials, userAgent, true
                )
            )
        )
    }

    /** Profiles need the zk operations as well as the sockets: the fetch is versioned. */
    val profiles: org.whispersystems.signalservice.api.profiles.ProfileApi by lazy {
        org.whispersystems.signalservice.api.profiles.ProfileApi(
            authenticated,
            unauthenticated,
            org.whispersystems.signalservice.internal.push.PushServiceSocket(
                configuration, credentials, userAgent, true
            ),
            org.signal.libsignal.zkgroup.profiles.ClientZkProfileOperations(
                org.signal.libsignal.zkgroup.ServerPublicParams(configuration.zkGroupServerPublicParams)
            )
        )
    }

    /**
     * Attachments come over plain HTTPS to a CDN, not over either websocket, so this needs its
     * own socket rather than reusing one of theirs.
     */
    val messageReceiver: org.whispersystems.signalservice.api.SignalServiceMessageReceiver by lazy {
        org.whispersystems.signalservice.api.SignalServiceMessageReceiver(
            org.whispersystems.signalservice.internal.push.PushServiceSocket(
                configuration, credentials, userAgent, true
            )
        )
    }

    fun connect() {
        Timber.i("signal socket: connecting as device %d", credentials.deviceId)
        // Registered BEFORE connecting, and the order is the whole point.
        //
        // Two separate things depend on this token. The health monitor will not send
        // keepalives without one -- `shouldSendKeepAlives()` is false while the set is empty,
        // so the keepalive thread never starts. And `connect()` itself schedules a *delayed
        // disconnect* if no token is registered at the moment it runs, on the reasoning that
        // a socket nobody is holding open is a socket nobody wants.
        //
        // Registering afterwards leaves both: no keepalives for the first pass, and a
        // teardown already scheduled. The symptom is a connection that drops every thirty to
        // forty seconds and reconnects on backoff -- messages still arrive, late, and it
        // reads as a flaky network rather than as an ordering mistake here.
        authenticated.registerKeepAliveToken(SignalWebSocket.FOREGROUND_KEEPALIVE)
        unauthenticated.registerKeepAliveToken(SignalWebSocket.FOREGROUND_KEEPALIVE)
        // registerKeepAliveToken() connects on its own -- registering a token *is* saying the
        // connection should be up -- so these are belt and braces rather than the thing that
        // opens the socket. Worth knowing when reading the log: the "connecting" line above
        // can precede a connection that the register call already started.
        authenticated.connect()
        unauthenticated.connect()
    }

    fun disconnect() {
        authenticated.disconnect()
        unauthenticated.disconnect()
    }

    companion object {
        /**
         * False. Stories are a whole feature -- their own storage, expiry and UI -- and this
         * app has none of it. Claiming otherwise would have the server deliver story traffic
         * that goes nowhere.
         */
        private const val ALLOW_STORIES = false

        private val DISCONNECT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30)

        /** signal-cli's value. Used only to size the operations helper. */
        private const val GROUP_MAX_SIZE = 1001
    }
}
