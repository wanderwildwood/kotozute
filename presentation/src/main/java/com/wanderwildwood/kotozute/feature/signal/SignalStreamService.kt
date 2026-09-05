package com.wanderwildwood.kotozute.feature.signal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.Navigator
import com.wanderwildwood.kotozute.repository.SignalRepository
import com.wanderwildwood.kotozute.util.Preferences
import dagger.android.AndroidInjection
import timber.log.Timber
import javax.inject.Inject

/**
 * Keeps the Signal connection open when nothing else would.
 *
 * The stream is a thread in the app's process, and nothing owns that process: it exists
 * because someone opened the app, or an SMS arrived, or the phone booted. When Android
 * reclaims it the thread goes too, and a Signal message then waits until something wakes the
 * app again. That it worked at all in the background was an accident of Desktop Sync -- a
 * foreground service, which kept the process alive and the thread with it.
 *
 * This is that accident made deliberate, and only when asked for: it runs while
 * "Keep Signal connected" is on. Off, [SignalSyncWorker] catches up periodically instead,
 * which bounds the delay rather than removing it.
 */
class SignalStreamService : android.app.Service() {

    @Inject lateinit var signalRepo: SignalRepository
    @Inject lateinit var prefs: Preferences
    @Inject lateinit var navigator: Navigator

    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Turning the switch off from the notification turns the setting off too.
            // Otherwise the service stops and boot brings it straight back, which reads as
            // the button not working.
            prefs.signalKeepConnected.set(false)
            stopSelf()
            return START_NOT_STICKY
        }

        // Both gates, because the service can be started by boot, by the setting, or by the
        // system restarting it -- and Signal being switched off entirely has to win.
        if (!prefs.signalEnabled.get() || !prefs.signalKeepConnected.get()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        // Idempotent: startStream refuses to start a second loop while one is running, so
        // this is safe on every redelivery.
        runCatching { signalRepo.startStream() }
            .onFailure { Timber.w(it, "signal: could not start the stream") }
        return START_STICKY
    }

    override fun onDestroy() {
        // The stream is deliberately left running. Something else may want it -- the
        // conversation list, Desktop Sync -- and stopping it here would cut that off.
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.signal_stream_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, SignalStreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.signal_stream_title))
            .setContentText(getString(R.string.signal_stream_text))
            .setSmallIcon(R.drawable.ic_notifications_black_24dp)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(0, getString(R.string.signal_stream_stop), stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "signal_stream"
        private const val NOTIFICATION_ID = 5190
        private const val ACTION_STOP = "ACTION_STOP"

        /** Starts or stops the service to match the setting. Safe to call at any time. */
        fun sync(context: Context, wanted: Boolean) {
            val intent = Intent(context, SignalStreamService::class.java)
            if (wanted) {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            } else {
                context.stopService(intent)
            }
        }
    }
}
