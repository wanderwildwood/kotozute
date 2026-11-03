/*
 * Foreground service hosting the Desktop Sync relay (DesktopSyncServer).
 * Started only by its switch in Settings. An update, or the system stopping the app, brings it
 * back as it was, as long as the phone has not restarted since; a reboot turns it off and
 * nothing starts it after one (no boot receiver, not sticky), as in Memo: a server that reads
 * every message should only be listening when somebody switched it on in this boot.
 */
package com.wanderwildwood.kotozute.feature.desktopsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.compat.SubscriptionManagerCompat
import com.wanderwildwood.kotozute.interactor.MarkRead
import com.wanderwildwood.kotozute.interactor.SendNewMessage
import com.wanderwildwood.kotozute.repository.ContactRepository
import com.wanderwildwood.kotozute.repository.ConversationRepository
import com.wanderwildwood.kotozute.repository.MessageRepository
import com.wanderwildwood.kotozute.util.Preferences
import dagger.android.AndroidInjection
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import com.wanderwildwood.kotozute.BuildConfig
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.inject.Inject

class DesktopSyncService : Service() {

    companion object {
        /**
         * A debug build listens on its own port. Installed alongside a release build --
         * which is the only sensible way to try a branch on the phone you actually use --
         * both would otherwise race for 8420 and whichever started second would die with
         * EADDRINUSE and no relay.
         */
        val PORT = if (BuildConfig.DEBUG) 8421 else 8420
        private const val NOTIFICATION_ID = 20260805

        /**
         * Bumped from "desktop_sync" to turn its badge off. A channel's behaviour is
         * frozen at creation — createNotificationChannel() on an existing id updates
         * only the name and description, so adding setShowBadge(false) to the old id
         * would have changed nothing on any device that had already run the relay.
         * The only way to alter it is a new channel, so the old one is deleted below.
         */
        private const val CHANNEL_ID = "desktop_sync_v2"
        private const val LEGACY_CHANNEL_ID = "desktop_sync"
        private const val ACTION_START = "ACTION_START"
        private const val ACTION_STOP = "ACTION_STOP"
        private const val ACTION_REBIND = "ACTION_REBIND"

        /**
         * Whether the relay is actually serving right now. Runtime-only on purpose:
         * the server never survives process death, so persisting this would let the
         * UI claim "Running" with nothing behind it. prefs.desktopSyncEnabled is what the
         * switch shows; a new process keeps it on only in the boot it was switched on in, and
         * starts the relay again ([afterProcessStart]).
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, DesktopSyncService::class.java)
                .setAction("${context.packageName}.$ACTION_START")
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Called when the app's process starts. The relay cannot have survived the old process.
         * After a reboot the switch goes off and stays off. In the same boot (an update, or the
         * system stopping the app) it stays on and the relay starts again: here if Android lets a
         * process in the background start it, otherwise on the update's own broadcast
         * ([DesktopSyncUpdateReceiver]) or when the app is next opened ([resumeIfOn]).
         */
        fun afterProcessStart(context: Context, prefs: Preferences) = resumeIfOn(context, prefs)

        /**
         * Starts the relay again if the switch is on and the phone has not restarted since it was
         * switched on; turns the switch off if it has. Does nothing when the switch is off.
         */
        fun resumeIfOn(context: Context, prefs: Preferences) {
            val saved = BootMark.decode(prefs.desktopSyncBootMark.get())
            when (DesktopSyncBoot.decide(prefs.desktopSyncEnabled.get(), isRunning, saved, BootMark.now(context))) {
                DesktopSyncBoot.Decision.NOTHING -> Unit
                DesktopSyncBoot.Decision.OFF -> prefs.desktopSyncEnabled.set(false)
                // Android 12 refuses a foreground service started from the background, except on
                // an update's broadcast or with the app open; the switch stays on either way, and
                // the next of those starts it.
                DesktopSyncBoot.Decision.START -> runCatching { start(context) }
                    .onFailure { Timber.i("Desktop Sync: not started from the background (${it.javaClass.simpleName}); it starts when the app opens") }
            }
        }

        @Volatile private var pairedBrowsers: PairedBrowsers? = null

        /**
         * The browsers paired with this phone, made on first use from the list kept (as
         * hashes) in the app's private preferences. The one token every browser shared before
         * is carried over once, as an entry that can only be traded for a browser's own token,
         * and erased from the preferences.
         */
        fun browsers(context: Context, prefs: Preferences): PairedBrowsers =
            pairedBrowsers ?: synchronized(this) {
                pairedBrowsers ?: PairedBrowsers(
                    now = { System.currentTimeMillis() },
                    initial = prefs.desktopSyncBrowsers.get(),
                    save = { prefs.desktopSyncBrowsers.set(it) },
                ).also { browsers ->
                    val shared = prefs.desktopSyncToken.get()
                    if (shared.isNotEmpty()) {
                        browsers.importLegacy(shared, context.getString(R.string.settings_desktop_sync_browser_legacy))
                        prefs.desktopSyncToken.delete()
                    }
                    pairedBrowsers = browsers
                }
            }

        /**
         * Bind the relay again, so a setting that shapes the socket takes effect.
         *
         * ⛔ **Not `stop()` then `start()`.** Those are two Intents and the teardown is
         * asynchronous, so the start can arrive while the old server still holds the port; the
         * new bind fails and the relay is left **down**, with nothing listening and no error in
         * front of anybody. Seen exactly once while switching HTTPS on, which is how this got
         * written: `adbd: failed to connect to socket 'tcp:8421': Connection refused`.
         *
         * One action instead, torn down and brought back up in order inside the service --
         * the same shape the old token reset used for the same reason.
         */
        fun rebind(context: Context) {
            val intent = Intent(context, DesktopSyncService::class.java)
                .setAction("${context.packageName}.$ACTION_REBIND")
            // Plain startService: if the relay is running it is already
            // foreground, and if it is not this just shuts back down.
            runCatching { context.startService(intent) }
        }

        fun stop(context: Context) {
            // Plain startService, not startForegroundService: this path tears the
            // service down, and promising a startForeground() we never make would
            // trip ForegroundServiceDidNotStartInTimeException.
            val intent = Intent(context, DesktopSyncService::class.java)
                .setAction("${context.packageName}.$ACTION_STOP")
            runCatching { context.startService(intent) }
        }

        /** Every paired browser is forgotten: its token stops working and open pages are closed. */
        fun forgetAllBrowsers(context: Context, prefs: Preferences) {
            DesktopSyncPairing.clear()
            browsers(context, prefs).removeAll()
        }

        private fun ipv4Addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .filterNot { it.isLoopbackAddress }
                .mapNotNull { it.hostAddress }
                .toList()
        }.getOrNull().orEmpty()

        /** True for Tailscale's CGNAT range, 100.64.0.0/10. */
        fun isTailscale(ip: String): Boolean {
            val parts = ip.split('.')
            if (parts.size != 4 || parts[0] != "100") return false
            val second = parts[1].toIntOrNull() ?: return false
            return second in 64..127
        }

        /**
         * True for a peer we'll talk to when "VPN only" is on: a tailnet address (CGNAT v4,
         * or Tailscale's fd7a:115c:a1e0::/48 v6), an address on the network of whatever
         * other VPN this phone is running (ZeroTier, WireGuard, Nebula...), or this device.
         *
         * Tailscale is recognised by its fixed ranges. Every other VPN picks its own, so for
         * those the answer comes from the tunnel itself: its address and prefix length say
         * which peers sit on the far side of it.
         *
         * The relay still binds every interface, deliberately: binding only the VPN address
         * would mean the server cannot start at all while the VPN is down, and Tailscale does
         * not come up by itself after a reboot. Refusing at the request layer keeps the
         * socket alive and simply turns everyone else away.
         */
        fun isAllowedPeer(ip: String?, context: Context?): Boolean =
            isAllowedPeer(ip, vpnSubnets(context))

        /** [isAllowedPeer] with the VPN networks already in hand, which is what the tests use. */
        fun isAllowedPeer(ip: String?, vpnNets: List<Subnet>): Boolean {
            val addr = ip?.substringBefore('%')?.lowercase() ?: return false
            if (addr == "127.0.0.1" || addr == "::1" || addr == "0:0:0:0:0:0:0:1") return true
            if (isTailscale(addr)) return true
            if (addr.startsWith("fd7a:115c:a1e0")) return true
            if (vpnNets.isEmpty()) return false
            val peer = literalAddress(addr) ?: return false
            return vpnNets.any { it.contains(peer) }
        }

        /** A network: an address on it and how many leading bits name the network. */
        class Subnet(val address: InetAddress, val prefix: Int) {
            fun contains(peer: InetAddress): Boolean {
                val a = address.address
                val b = peer.address
                // A v4 peer never matches a v6 network, or the reverse.
                if (a.size != b.size) return false
                // An interface can report a length that makes no sense; trust nothing then.
                if (prefix < 0 || prefix > a.size * 8) return false
                val whole = prefix / 8
                for (i in 0 until whole) if (a[i] != b[i]) return false
                val rest = prefix % 8
                if (rest == 0) return true
                val mask = (0xFF shl (8 - rest)) and 0xFF
                return (a[whole].toInt() and mask) == (b[whole].toInt() and mask)
            }

            override fun toString() = "${address.hostAddress}/$prefix"
        }

        /**
         * The address as an InetAddress, only if it is already a numeric literal. The peer
         * address comes straight off the socket and always is, but getByName on anything
         * else would go and ask DNS, and a gate should never wait on a lookup.
         */
        private fun literalAddress(addr: String): InetAddress? {
            if (!Regex("^[0-9a-f.:]+$").matches(addr)) return null
            return runCatching { InetAddress.getByName(addr) }.getOrNull()
        }

        /**
         * The networks on the far side of every VPN this phone has up, other than Tailscale's,
         * which is recognised by range instead.
         *
         * Two kinds are left out, because trusting them would let in exactly what the switch
         * exists to refuse:
         * - one that also holds a Wi-Fi or wired address of this phone. A VPN numbered the same
         *   as the home network would otherwise admit every device in the house.
         * - one wider than a /8 (v4) or a /32 (v6). A tunnel that claims most of the internet
         *   as its own network is describing a route, not a set of peers.
         *
         * Read at most every few seconds. Every request asks, including each image on a page,
         * and the answer only changes when a VPN comes up or goes down.
         */
        fun vpnSubnets(context: Context?): List<Subnet> {
            val now = android.os.SystemClock.elapsedRealtime()
            cachedVpnSubnets?.let { (at, nets) -> if (now - at < VPN_CACHE_MS) return nets }
            val nets = readVpnSubnets(context)
            cachedVpnSubnets = now to nets
            return nets
        }

        @Volatile private var cachedVpnSubnets: Pair<Long, List<Subnet>>? = null
        private const val VPN_CACHE_MS = 5_000L

        private fun readVpnSubnets(context: Context?): List<Subnet> {
            val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return emptyList()
            return runCatching {
                fun subnetsOf(vpn: Boolean): List<Subnet> = cm.allNetworks
                    .filter { network ->
                        val caps = cm.getNetworkCapabilities(network) ?: return@filter false
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == vpn &&
                            (vpn || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                    }
                    .mapNotNull { cm.getLinkProperties(it)?.interfaceName }
                    .distinct()
                    .flatMap { iface ->
                        NetworkInterface.getByName(iface)?.interfaceAddresses.orEmpty()
                            .mapNotNull { ia ->
                                val addr = ia.address ?: return@mapNotNull null
                                if (addr.isLoopbackAddress || addr.isLinkLocalAddress) null
                                else Subnet(addr, ia.networkPrefixLength.toInt())
                            }
                    }

                val local = subnetsOf(vpn = false)
                subnetsOf(vpn = true)
                    .filterNot { net ->
                        val host = net.address.hostAddress?.lowercase().orEmpty()
                        isTailscale(host) || host.startsWith("fd7a:115c:a1e0")
                    }
                    .filter { net -> net.prefix >= (if (net.address is Inet4Address) 8 else 32) }
                    .filterNot { net -> local.any { net.contains(it.address) || it.contains(net.address) } }
            }.onFailure {
                // Empty means "no VPN to trust", which refuses more, never less.
                Timber.w(it, "could not read the VPN networks; trusting none of them")
            }.getOrNull().orEmpty()
        }

        /**
         * The Tailscale IPv4 address, if Tailscale is connected.
         *
         * Asks for the VPN network specifically rather than scanning interfaces, because
         * 100.64.0.0/10 is carrier-grade NAT: a phone on mobile data can hold an address
         * in that range that has nothing to do with Tailscale, and an interface scan
         * cannot tell the two apart.
         */
        fun findTailscaleAddress(context: Context?): String? {
            addressesOn(context, NetworkCapabilities.TRANSPORT_VPN)
                ?.firstOrNull(::isTailscale)
                ?.let { return it }
            // Only if the system would not say. Never worse than the old behaviour.
            return ipv4Addresses().firstOrNull(::isTailscale)
        }

        /** Kept for callers with no Context to hand; prefer the overload that has one. */
        fun findTailscaleAddress(): String? = findTailscaleAddress(null)

        /**
         * This phone's address on a VPN other than Tailscale, if one is up: ZeroTier and the
         * like. Only an address on a network [vpnSubnets] would let a computer in from, so a
         * VPN numbered like the home Wi-Fi is not offered as a way in and then refused.
         */
        fun findOtherVpnAddress(context: Context?): String? {
            val trusted = vpnSubnets(context)
            if (trusted.isEmpty()) return null
            return addressesOn(context, NetworkCapabilities.TRANSPORT_VPN)
                ?.filterNot(::isTailscale)
                ?.firstOrNull { addr ->
                    val a = literalAddress(addr) ?: return@firstOrNull false
                    trusted.any { it.contains(a) }
                }
        }

        /** Tailscale's address if it is up, otherwise any other VPN's. */
        fun findVpnAddress(context: Context?): String? =
            findTailscaleAddress(context) ?: findOtherVpnAddress(context)

        /**
         * A normal private LAN address, if any. Worth surfacing because the relay
         * listens on all interfaces, so it stays reachable over home Wi-Fi even when
         * Tailscale isn't running — which it isn't right after a reboot, since
         * MuditaOS has no always-on VPN toggle.
         */
        fun findLanAddress(context: Context?): String? {
            // Wi-Fi (or wired) specifically. Carriers hand out private addresses too, and
            // an interface scan returns whichever the system happens to enumerate first --
            // which on a phone with mobile data alongside Wi-Fi was reliably the cellular
            // one. The relay was always reachable on Wi-Fi; only the address we printed
            // was wrong, which is the hardest kind of wrong to diagnose.
            for (transport in intArrayOf(
                NetworkCapabilities.TRANSPORT_WIFI,
                NetworkCapabilities.TRANSPORT_ETHERNET
            )) {
                addressesOn(context, transport)
                    ?.firstOrNull { !isTailscale(it) && isPrivate(it) }
                    ?.let { return it }
            }
            // With a Context we trust the answer, including "there isn't one": a cellular
            // address here would send someone to a page that can never load.
            if (context != null) return null
            return ipv4Addresses().firstOrNull { !isTailscale(it) && isPrivate(it) }
        }

        /** Kept for callers with no Context to hand; prefer the overload that has one. */
        fun findLanAddress(): String? = findLanAddress(null)

        /**
         * Every address this phone can be reached on right now, labelled, most useful
         * first. The relay binds all interfaces, so on a phone with both Wi-Fi and
         * Tailscale up there is more than one right answer and no way to know from here
         * which one the computer at the other end can see. Naming one and hiding the rest
         * is what made the wrong address so hard to diagnose: the page simply never loaded,
         * with nothing to suggest another was available.
         */
        fun reachableAddresses(context: Context?): List<Pair<String, String>> {
            val found = LinkedHashMap<String, String>()   // address -> label, first label wins

            // Not the Map method of the same name: that one is API 24, and every module
            // here still declares minSdk 23, where it throws NoSuchMethodError -- with
            // nothing on this path to catch it, so opening Settings would take the app
            // down on an Android 6 phone. The build says nothing either, because
            // presentation sets abortOnError false.
            fun keep(address: String, label: String) {
                if (!found.containsKey(address)) found[address] = label
            }

            // What the carrier gave us, so it can be ruled out below. 100.64.0.0/10 is
            // carrier-grade NAT and AT&T and Verizon both hand out addresses in it, so an
            // interface scan cannot tell a real tailnet address from a mobile-data one --
            // and some carriers use 10.x, which reads as an ordinary private LAN. Either
            // way it is an address no computer can reach, and offering it sends the user
            // to a page that will never load, in a list whose whole purpose is to stop
            // exactly that.
            val cellular = addressesOn(context, NetworkCapabilities.TRANSPORT_CELLULAR).orEmpty().toSet()

            findTailscaleAddress(context)
                ?.takeIf { it !in cellular }
                ?.let { keep(it, LABEL_TAILSCALE) }
            findOtherVpnAddress(context)
                ?.takeIf { it !in cellular }
                ?.let { keep(it, LABEL_VPN) }
            addressesOn(context, NetworkCapabilities.TRANSPORT_WIFI)
                ?.filter { !isTailscale(it) && isPrivate(it) }
                ?.forEach { keep(it, LABEL_WIFI) }
            addressesOn(context, NetworkCapabilities.TRANSPORT_ETHERNET)
                ?.filter { !isTailscale(it) && isPrivate(it) }
                ?.forEach { keep(it, LABEL_ETHERNET) }
            // Only where the system would not answer. A cellular address is still left out:
            // it would send someone to a page that can never load. Labelled neutrally,
            // because an interface scan cannot tell Wi-Fi from wired and calling it Wi-Fi
            // would be claiming to know something we do not.
            if (found.isEmpty()) {
                ipv4Addresses()
                    .firstOrNull { !isTailscale(it) && isPrivate(it) && it !in cellular }
                    ?.let { keep(it, LABEL_LOCAL) }
            }
            return found.map { (address, label) -> label to address }
        }

        /** The one place the scheme is decided: the socket is TLS exactly when "Use HTTPS" is on. */
        fun scheme(tls: Boolean): String = if (tls) "https" else "http"

        /**
         * A link to this phone's relay. [path] is empty or starts with a slash. It carries no
         * token: a browser proves itself with the six-digit code once, and keeps its own.
         */
        fun linkUrl(tls: Boolean, host: String, port: Int, path: String = ""): String =
            "${scheme(tls)}://$host:$port$path"

        const val LABEL_TAILSCALE = "Tailscale"
        const val LABEL_VPN = "VPN"
        /** The labels whose addresses "VPN only" lets through; the rest would answer 403. */
        val VPN_LABELS = setOf(LABEL_TAILSCALE, LABEL_VPN)
        const val LABEL_WIFI = "Wi-Fi"
        const val LABEL_ETHERNET = "Wired"
        const val LABEL_LOCAL = "Local network"

        private fun isPrivate(ip: String): Boolean =
            ip.startsWith("192.168.") ||
                ip.startsWith("10.") ||
                Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(ip)

        /**
         * IPv4 addresses on every network offering [transport], or null if unknown.
         *
         * Asks the system only which interface each network uses, then reads the interface
         * directly. LinkProperties.getLinkAddresses() is compiled against a newer SDK than
         * this phone runs and throws NoSuchMethodError on API 31 -- caught, and so
         * indistinguishable from "there is no Wi-Fi" unless you go looking. getInterfaceName
         * has been stable since API 21.
         *
         * A VPN network reports its underlying transports as well as TRANSPORT_VPN, so
         * asking for Wi-Fi while Tailscale is up could match the tunnel and return tun0's
         * address -- which then failed the "not a tailnet address" test, leaving the phone
         * looking like it had no Wi-Fi at all. Unless the VPN transport is what was asked
         * for, VPN networks are skipped.
         *
         * Every matching network, not the first: a phone can hold more than one, and the
         * one enumerated first is not reliably the one that answers.
         */
        private fun addressesOn(context: Context?, transport: Int): List<String>? {
            val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return null
            return runCatching {
                cm.allNetworks
                    .filter { network ->
                        val caps = cm.getNetworkCapabilities(network) ?: return@filter false
                        if (!caps.hasTransport(transport)) return@filter false
                        transport == NetworkCapabilities.TRANSPORT_VPN ||
                            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    }
                    .mapNotNull { cm.getLinkProperties(it)?.interfaceName }
                    .distinct()
                    .flatMap { iface ->
                        NetworkInterface.getByName(iface)?.inetAddresses?.asSequence()
                            ?.filterIsInstance<Inet4Address>()
                            ?.filterNot { addr -> addr.isLoopbackAddress }
                            ?.mapNotNull { addr -> addr.hostAddress }
                            ?.toList()
                            .orEmpty()
                    }
                    .distinct()
                    .ifEmpty { null }
            }.onFailure {
                // Null means "no address to offer for this transport", and the caller moves
                // to the next one. A transport that cannot be read is skipped rather than
                // guessed at.
                Timber.w(it, "could not read addresses for transport %d; skipping it", transport)
            }
                .getOrNull()
        }
    }

    @Inject lateinit var conversationRepository: ConversationRepository
    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var contactRepository: ContactRepository
    @Inject lateinit var markRead: MarkRead
    @Inject lateinit var sendNewMessage: SendNewMessage
    @Inject lateinit var subscriptionManager: SubscriptionManagerCompat
    @Inject lateinit var prefs: Preferences
    @Inject lateinit var syncMessages: com.wanderwildwood.kotozute.interactor.SyncMessages
    @Inject lateinit var scheduledMessageRepository: com.wanderwildwood.kotozute.repository.ScheduledMessageRepository
    @Inject lateinit var updateScheduledMessageAlarms: com.wanderwildwood.kotozute.interactor.UpdateScheduledMessageAlarms
    @Inject lateinit var signalRepository: com.wanderwildwood.kotozute.repository.SignalRepository

    private val disposables = CompositeDisposable()
    private var server: DesktopSyncServer? = null

    /**
     * Text of the notification as currently posted, so a re-post with identical content can be
     * skipped. Every re-post is a fresh onNotificationPosted() to any notification listener, and
     * a launcher that badges from those (inkOS does) reads it as new mail for this app.
     */
    private var postedNotificationText: String? = null

    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "$packageName.$ACTION_START" -> startRelay()
            "$packageName.$ACTION_STOP" -> stopRelay()
            "$packageName.$ACTION_REBIND" -> rebindRelay()
            // Anything else, a stray or empty intent included, ends it: the relay never comes
            // back by itself.
            else -> stopRelay()
        }
        return START_NOT_STICKY
    }

    private fun startRelay() {
        // Android gives a startForegroundService() only a few seconds to call
        // startForeground(), so do it FIRST — before binding the port, which can
        // fail — or the platform kills us with
        // ForegroundServiceDidNotStartInTimeException. This one is unconditional:
        // the platform requires the call, whatever the notification already says.
        postNotification(force = true)

        if (server != null) return // already running

        val newServer = DesktopSyncServer(
            port = PORT,
            context = applicationContext,
            browsers = browsers(applicationContext, prefs),
            conversationRepository = conversationRepository,
            messageRepository = messageRepository,
            contactRepository = contactRepository,
            markRead = markRead,
            sendNewMessage = sendNewMessage,
            subscriptionManager = subscriptionManager,
            signalRepository = signalRepository,
            signalEnabled = { prefs.signalEnabled.get() },
            // Read live, so flipping the setting takes effect on the next request
            // instead of needing the relay stopped and started again.
            vpnOnly = { prefs.desktopSyncVpnOnly.get() },
            blockingManager = { prefs.blockingManager.get() },
            prefs = prefs,
            syncMessages = syncMessages,
            scheduledMessageRepository = scheduledMessageRepository,
            updateScheduledMessageAlarms = updateScheduledMessageAlarms,
        )

        // Timeout 0 = no socket read timeout. A push WebSocket sits idle by design,
        // and the default 5s timeout tears it down with a SocketTimeoutException.
        val started = runCatching { newServer.start(0, false) }
        if (started.isFailure) {
            Timber.e(started.exceptionOrNull(), "Desktop Sync failed to start")
            prefs.desktopSyncEnabled.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            postedNotificationText = null
            stopSelf()
            return
        }

        server = newServer
        isRunning = true
        prefs.desktopSyncEnabled.set(true)
        // The boot it went on in: an update in this boot brings it back, a reboot does not.
        prefs.desktopSyncBootMark.set(BootMark.now(applicationContext).encode())
        // Refresh the notification now that the port is bound and the address is known —
        // but only if that actually changed the text.
        postNotification()

        // getUnmanagedConversations() already applies subscribeOn(mainThread) (required:
        // Realm's findAllAsync change listener needs a Looper) and observeOn(io), and the
        // subscribeOn closest to the source wins — so don't re-specify schedulers here.
        disposables += conversationRepository.getUnmanagedConversations()
            .subscribe({
                Timber.i("Desktop Sync: conversations changed -> pushing to ${newServer.socketCount()} client(s)")
                newServer.notifyChanged()
            }, Timber::w)
    }

    /**
     * Tear the server down and bring it straight back up, in that order and in one place.
     *
     * ⚠ `startRelay()` bails out early when a server already exists, so the teardown is not
     * optional -- without it the old socket keeps serving, with the old token or the old
     * scheme, while everything reports success.
     *
     * Nothing bound means nothing to rebind: whatever changed is read at the next start, and
     * an idle service is not left behind.
     */
    private fun rebindRelay() {
        if (server == null) {
            stopSelf()
            return
        }
        disposables.clear()
        server?.stop()
        server = null
        isRunning = false
        startRelay()
    }

    private fun stopRelay() {
        disposables.clear()
        server?.stop()
        server = null
        isRunning = false
        prefs.desktopSyncEnabled.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        postedNotificationText = null
        stopSelf()
    }

    override fun onDestroy() {
        disposables.clear()
        server?.stop()
        server = null
        // The switch follows the server: when the service is stopped, Desktop Sync is off. (A
        // process that is killed, by an update or the system, never gets here; its switch is
        // dealt with when the next process starts.)
        isRunning = false
        prefs.desktopSyncEnabled.set(false)
        super.onDestroy()
    }

    /**
     * Posts the ongoing notification, skipping the post entirely when the text is unchanged.
     * [force] is for the startForeground() the platform demands right after a start, which has
     * to happen even when the content is identical to what is already showing.
     */
    private fun postNotification(force: Boolean = false) {
        val text = notificationText()
        if (!force && text == postedNotificationText) return
        startForeground(NOTIFICATION_ID, buildNotification(text))
        postedNotificationText = text
    }

    private fun notificationText(): String {
        val address = findTailscaleAddress(this) ?: findLanAddress(this)
        return if (address != null) "Listening on $address:$PORT" else "Waiting for a network…"
    }

    private fun buildNotification(contentText: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Desktop Sync", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        }

        val stopIntent = Intent(this, DesktopSyncService::class.java)
            .setAction("$packageName.$ACTION_STOP")
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Desktop Sync running")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_sync_black_24dp)
            .setColor(android.graphics.Color.BLACK)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // This is a running server, not mail. inkOS badges the launcher icon from a
            // NotificationListenerService, and a listener that never consults
            // Ranking.canShowBadge() has to be talked out of counting this some other
            // way: CATEGORY_SERVICE says what it is, and an explicit count of 0 stops
            // anything reading Notification.number from finding a 1 in it.
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setNumber(0)
            // Never alert again on a re-post, and don't carry a timestamp that makes each
            // restart look like something that just arrived.
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setWhen(0)
            .setSilent(true)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }
}
