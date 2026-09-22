package com.wanderwildwood.kotozute.signalstore

import org.signal.core.models.ServiceId
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
    private val context: android.content.Context,
    private val accounts: SignalAccountStore,
    private val userAgent: String,
    private val configuration: org.signal.network.config.SignalServiceConfiguration =
        SignalNetworkConfig.configuration(),
    /**
     * Called when the server refuses this device outright. See
     * [SignalSocketHealthMonitor.onRejected] -- given to both sockets, because a deprecated
     * client is refused on either, even though only the authenticated one can fail to
     * authenticate.
     */
    private val onRejected: (String) -> Unit = {},
    /**
     * Called with whether the server says the account's primary has gone idle.
     *
     * ⚠ Given only to the authenticated socket. An alert on the unauthenticated one is not
     * about this account, and upstream returns before reading them there.
     */
    private val onPrimaryIdle: (Boolean) -> Unit = {},
    /**
     * Called with whether this phone's own socket is reaching for the server.
     *
     * ⚠ The authenticated socket only, like [onPrimaryIdle]. The unauthenticated one comes
     * and goes with sealed-sender sends; its state says nothing about whether this phone can
     * receive, and reporting it would make the composer flicker on every send.
     */
    private val onConnecting: (Boolean) -> Unit = {}
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
        // ⛔ **libsignal carries its OWN environment, separate from the REST URLs**, and this
        // was hardcoded to PRODUCTION while `SignalNetworkConfig` was pointed at staging.
        //
        // The result is the most confusing shape a bug can take: registration succeeded --
        // it goes over the REST client, which *was* on staging -- and then the authenticated
        // socket and the pre-key upload came here and asked **production** about an account
        // production had never heard of. The server's answer was
        // `DeviceDeregisteredException: device was deregistered`, which reads as "something
        // deregistered your brand new account" rather than "you asked the wrong server".
        // The unauthenticated socket connected happily throughout, because it authenticates
        // nothing, which made it look like the network was fine.
        //
        // Upstream keeps the same two axes and sets them together: its staging flavour has
        // both the staging URLs and `LIBSIGNAL_NET_ENV = Network.Environment.STAGING`.
        //
        // ⚠ `BuildVariant` is a **different axis** and stays PRODUCTION -- it is about which
        // build of libsignal this is, not which servers it talks to.
        val environment = when (SignalNetworkConfig.environment) {
            SignalNetworkConfig.Environment.STAGING -> Network.Environment.STAGING
            SignalNetworkConfig.Environment.PRODUCTION -> Network.Environment.PRODUCTION
        }
        Network(environment, userAgent, emptyMap(), Network.BuildVariant.PRODUCTION)
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
        val timer = AlarmSleepTimer(context)
        val monitor = SignalSocketHealthMonitor(timer, onRejected, onPrimaryIdle, onConnecting)
        SignalWebSocket.AuthenticatedWebSocket(
            { LibSignalChatConnection("normal", network, credentials, ALLOW_STORIES, monitor) },
            { true },
            timer,
            DISCONNECT_TIMEOUT_MS
        ).also(monitor::monitor)
    }

    val unauthenticated: SignalWebSocket.UnauthenticatedWebSocket by lazy {
        val timer = AlarmSleepTimer(context)
        // No keepalive sender on this one: see [SignalSocketHealthMonitor.sendKeepAlives].
        // Upstream passes false here and true for the authenticated socket.
        val monitor = SignalSocketHealthMonitor(timer, onRejected, sendKeepAlives = false)
        SignalWebSocket.UnauthenticatedWebSocket(
            { LibSignalChatConnection("unidentified", network, null, ALLOW_STORIES, monitor) },
            { true },
            timer,
            DISCONNECT_TIMEOUT_MS
        ).also(monitor::monitor)
    }

    val keys: KeysApi by lazy { KeysApi(authenticated, unauthenticated) }

    /** The account itself: what this device can do, and what it is. */
    val account: org.whispersystems.signalservice.api.account.AccountApi by lazy {
        org.whispersystems.signalservice.api.account.AccountApi(authenticated)
    }

    /**
     * The zk side of groups, on its own as well as inside [groups].
     *
     * Creating a group needs it directly -- `createNewGroup` builds the encrypted group from
     * the members' credentials before anything is sent -- where reading one only ever needed
     * the api that wraps it.
     */
    val groupOperations: org.whispersystems.signalservice.api.groupsv2.GroupsV2Operations by lazy {
        org.whispersystems.signalservice.api.groupsv2.GroupsV2Operations(
            org.whispersystems.signalservice.api.groupsv2.ClientZkOperations.create(configuration),
            GROUP_MAX_SIZE
        )
    }

    /** Group operations need the zk parameters as well as the socket. */
    val groups: org.whispersystems.signalservice.api.groupsv2.GroupsV2Api by lazy {
        org.whispersystems.signalservice.api.groupsv2.GroupsV2Api(
            authenticated,
            org.whispersystems.signalservice.internal.push.PushServiceSocket(
                configuration, credentials, userAgent, true
            ),
            groupOperations
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
