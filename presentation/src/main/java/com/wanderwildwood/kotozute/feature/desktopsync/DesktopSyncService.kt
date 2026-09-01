/*
 * Foreground service hosting the Desktop Sync relay (DesktopSyncServer).
 * Manual start/stop only, from the Settings screen — never auto-starts.
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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import javax.inject.Inject

class DesktopSyncService : Service() {

    companion object {
        const val PORT = 8420
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
        private const val ACTION_RESET_TOKEN = "ACTION_RESET_TOKEN"

        /**
         * Whether the relay is actually serving right now. Runtime-only on purpose:
         * the server never survives process death, so persisting this would let the
         * UI claim "Running" with nothing behind it. The **persisted*_ flag
         * (prefs.desktopSyncEnabled) means "David wants this on" and is what drives
         * auto-restore after a reboot, an app update, or an Android process kill.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, DesktopSyncService::class.java)
                .setAction("${context.packageName}.$ACTION_START")
            ContextCompat.startForegroundService(context, intent)
        }

        /** Bring the relay back up if it was left switched on. Safe to call spuriously. */
        fun restoreIfEnabled(context: Context, prefs: Preferences) {
            if (prefs.desktopSyncEnabled.get() && !isRunning) {
                runCatching { start(context) }
            }
        }

        fun stop(context: Context) {
            // Plain startService, not startForegroundService: this path tears the
            // service down, and promising a startForeground() we never make would
            // trip ForegroundServiceDidNotStartInTimeException.
            val intent = Intent(context, DesktopSyncService::class.java)
                .setAction("${context.packageName}.$ACTION_STOP")
            runCatching { context.startService(intent) }
        }

        /**
         * Throw away the current token and mint a new one, rebinding the relay so the
         * new token takes effect immediately. Every existing bookmark and open browser
         * tab stops working until it is reopened on the new URL — which is the point.
         */
        fun resetToken(context: Context) {
            val intent = Intent(context, DesktopSyncService::class.java)
                .setAction("${context.packageName}.$ACTION_RESET_TOKEN")
            // Plain startService: this never promises a startForeground() we don't make.
            // If the relay is running it is already foreground; if it isn't, the handler
            // just rewrites the token and shuts back down.
            runCatching { context.startService(intent) }
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
        const val TOKEN_LENGTH = 24

        fun isTailscale(ip: String): Boolean {
            val parts = ip.split('.')
            if (parts.size != 4 || parts[0] != "100") return false
            val second = parts[1].toIntOrNull() ?: return false
            return second in 64..127
        }

        /**
         * True for a peer we'll talk to when "Tailscale only" is on: a tailnet address
         * (CGNAT v4, or Tailscale's fd7a:115c:a1e0::/48 v6) or this device itself.
         *
         * The relay still binds every interface, deliberately: binding only the tailnet
         * address would mean the server cannot start at all while Tailscale is down, and
         * Tailscale does not come up by itself after a reboot. Refusing at the request
         * layer keeps the socket alive and simply turns non-tailnet callers away.
         */
        fun isAllowedPeer(ip: String?): Boolean {
            val addr = ip?.substringBefore('%')?.lowercase() ?: return false
            if (addr == "127.0.0.1" || addr == "::1" || addr == "0:0:0:0:0:0:0:1") return true
            if (isTailscale(addr)) return true
            return addr.startsWith("fd7a:115c:a1e0")
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

        private fun isPrivate(ip: String): Boolean =
            ip.startsWith("192.168.") ||
                ip.startsWith("10.") ||
                Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(ip)

        /** IPv4 addresses on the first network offering [transport], or null if unknown. */
        /**
         * IPv4 addresses on the first network offering [transport], or null if unknown.
         *
         * Asks the system only which interface that network uses, then reads the interface
         * directly. LinkProperties.getLinkAddresses() is compiled against a newer SDK than
         * this phone runs and throws NoSuchMethodError on API 31 -- caught, and so
         * indistinguishable from "there is no Wi-Fi" unless you go looking. getInterfaceName
         * has been stable since API 21.
         */
        private fun addressesOn(context: Context?, transport: Int): List<String>? {
            val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return null
            return runCatching {
                val network = cm.allNetworks.firstOrNull {
                    cm.getNetworkCapabilities(it)?.hasTransport(transport) == true
                } ?: return@runCatching null
                val iface = cm.getLinkProperties(network)?.interfaceName
                    ?: return@runCatching null
                NetworkInterface.getByName(iface)?.inetAddresses?.asSequence()
                    ?.filterIsInstance<Inet4Address>()
                    ?.filterNot { it.isLoopbackAddress }
                    ?.mapNotNull { it.hostAddress }
                    ?.toList()
            }.onFailure { Timber.w(it, "could not read addresses for transport %d", transport) }
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
            "$packageName.$ACTION_RESET_TOKEN" -> resetToken()
            // A null intent means Android restarted us itself after a process kill
            // (START_STICKY). Come back up only if the relay was left switched on.
            null -> if (prefs.desktopSyncEnabled.get()) startRelay() else stopRelay()
        }
        return START_STICKY
    }

    private fun startRelay() {
        // Android gives a startForegroundService() only a few seconds to call
        // startForeground(), so do it FIRST — before binding the port, which can
        // fail — or the platform kills us with
        // ForegroundServiceDidNotStartInTimeException. This one is unconditional:
        // the platform requires the call, whatever the notification already says.
        postNotification(force = true)

        if (server != null) return // already running

        var token = prefs.desktopSyncToken.get()
        if (token.isEmpty()) {
            token = generateToken()
            prefs.desktopSyncToken.set(token)
        }

        val newServer = DesktopSyncServer(
            port = PORT,
            context = applicationContext,
            token = token,
            conversationRepository = conversationRepository,
            messageRepository = messageRepository,
            contactRepository = contactRepository,
            markRead = markRead,
            sendNewMessage = sendNewMessage,
            subscriptionManager = subscriptionManager,
            // Read live, so flipping the setting takes effect on the next request
            // instead of needing the relay stopped and started again.
            tailscaleOnly = { prefs.desktopSyncTailscaleOnly.get() },
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

    private fun resetToken() {
        prefs.desktopSyncToken.set(generateToken())
        if (server == null) {
            // Nothing bound, so the new token will simply be picked up on next start.
            // Don't leave an idle service behind.
            stopSelf()
            return
        }
        // startRelay() bails out early when a server already exists, so tear the old
        // one down first — otherwise it would keep serving the old token.
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
        // Note: deliberately does NOT clear prefs.desktopSyncEnabled — if the process
        // is being killed while the relay was on, that persisted intent is exactly
        // what brings it back on next launch/boot.
        isRunning = false
        super.onDestroy()
    }

    private fun generateToken(): String {
        // Crockford's alphabet: no I, L, O or U, so nothing in a token can be mistaken for
        // anything else in it. Base64 put lI1 and O0 in the same string and this link gets
        // read off a phone screen and typed into a computer, which made telling them apart
        // somebody else's problem. 24 characters of it is 120 bits, which is ample.
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        val random = SecureRandom()
        return buildString(TOKEN_LENGTH) {
            repeat(TOKEN_LENGTH) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
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
