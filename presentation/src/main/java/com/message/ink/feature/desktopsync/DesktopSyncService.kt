/*
 * Foreground service hosting the Desktop Sync relay (DesktopSyncServer).
 * Manual start/stop only, from the Settings screen — never auto-starts.
 */
package com.message.ink.feature.desktopsync

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
import com.message.ink.R
import com.message.ink.interactor.MarkRead
import com.message.ink.repository.ContactRepository
import com.message.ink.repository.ConversationRepository
import com.message.ink.repository.MessageRepository
import com.message.ink.util.Preferences
import dagger.android.AndroidInjection
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import javax.inject.Inject

class DesktopSyncService : Service() {

    companion object {
        const val PORT = 8420
        private const val NOTIFICATION_ID = 20260805
        private const val CHANNEL_ID = "desktop_sync"
        private const val ACTION_START = "ACTION_START"
        private const val ACTION_STOP = "ACTION_STOP"

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

        private fun ipv4Addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .filterNot { it.isLoopbackAddress }
                .mapNotNull { it.hostAddress }
                .toList()
        }.getOrNull().orEmpty()

        /** True for Tailscale's CGNAT range, 100.64.0.0/10. */
        private fun isTailscale(ip: String): Boolean {
            val parts = ip.split('.')
            if (parts.size != 4 || parts[0] != "100") return false
            val second = parts[1].toIntOrNull() ?: return false
            return second in 64..127
        }

        /** The Tailscale IPv4 address, if Tailscale is connected. Works from anywhere. */
        fun findTailscaleAddress(): String? = ipv4Addresses().firstOrNull(::isTailscale)

        /**
         * A normal private LAN address, if any. Worth surfacing because the relay
         * listens on all interfaces, so it stays reachable over home Wi-Fi even when
         * Tailscale isn't running — which it isn't right after a reboot, since
         * MuditaOS has no always-on VPN toggle.
         */
        fun findLanAddress(): String? = ipv4Addresses()
            .firstOrNull { ip ->
                !isTailscale(ip) && (
                    ip.startsWith("192.168.") ||
                        ip.startsWith("10.") ||
                        Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(ip)
                    )
            }
    }

    @Inject lateinit var conversationRepository: ConversationRepository
    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var contactRepository: ContactRepository
    @Inject lateinit var markRead: MarkRead
    @Inject lateinit var prefs: Preferences

    private val disposables = CompositeDisposable()
    private var server: DesktopSyncServer? = null

    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "$packageName.$ACTION_START" -> startRelay()
            "$packageName.$ACTION_STOP" -> stopRelay()
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
        // ForegroundServiceDidNotStartInTimeException.
        startForeground(NOTIFICATION_ID, buildNotification())

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
        )

        // Timeout 0 = no socket read timeout. A push WebSocket sits idle by design,
        // and the default 5s timeout tears it down with a SocketTimeoutException.
        val started = runCatching { newServer.start(0, false) }
        if (started.isFailure) {
            Timber.e(started.exceptionOrNull(), "Desktop Sync failed to start")
            prefs.desktopSyncEnabled.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        server = newServer
        isRunning = true
        prefs.desktopSyncEnabled.set(true)
        // Refresh the notification now that the port is bound and the address is known.
        startForeground(NOTIFICATION_ID, buildNotification())

        // getUnmanagedConversations() already applies subscribeOn(mainThread) (required:
        // Realm's findAllAsync change listener needs a Looper) and observeOn(io), and the
        // subscribeOn closest to the source wins — so don't re-specify schedulers here.
        disposables += conversationRepository.getUnmanagedConversations()
            .subscribe({
                Timber.i("Desktop Sync: conversations changed -> pushing to ${newServer.socketCount()} client(s)")
                newServer.notifyChanged()
            }, Timber::w)
    }

    private fun stopRelay() {
        disposables.clear()
        server?.stop()
        server = null
        isRunning = false
        prefs.desktopSyncEnabled.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
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
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Desktop Sync", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, DesktopSyncService::class.java)
            .setAction("$packageName.$ACTION_STOP")
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val address = findTailscaleAddress() ?: findLanAddress()
        val contentText = if (address != null) "Listening on $address:$PORT" else "Waiting for a network…"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Desktop Sync running")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_sync_black_24dp)
            .setColor(android.graphics.Color.BLACK)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }
}
