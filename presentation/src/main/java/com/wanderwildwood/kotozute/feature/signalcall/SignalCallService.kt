package com.wanderwildwood.kotozute.feature.signalcall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.model.SignalThread
import com.wanderwildwood.kotozute.repository.SignalCallState
import com.wanderwildwood.kotozute.repository.SignalRepository
import dagger.android.AndroidInjection
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.realm.Realm
import timber.log.Timber
import javax.inject.Inject

/**
 * Keeps a Signal call alive, and rings for one.
 *
 * A foreground service for as long as a call is ringing or up: upstream's `ActiveCallManager`,
 * trimmed. While it rings it plays the ringtone and posts a call notification whose full-screen
 * intent puts [SignalCallActivity] over the lock screen, the way a phone call comes up. Once
 * answered it becomes the ongoing-call notification, and it is what keeps the microphone open
 * with the screen off.
 */
class SignalCallService : Service() {

    @Inject lateinit var signalRepo: SignalRepository

    private var watching: Disposable? = null
    private var ringer: IncomingRinger? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var inForeground = false
    private var withMicrophone = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DECLINE -> signalRepo.calls().decline()
            ACTION_HANG_UP -> signalRepo.calls().hangUp()
            // The call screen asks for this when the person answers. Answering is the moment
            // the app is in front of them, which is when Android lets a service start using
            // the microphone and keep using it after the screen goes off.
            ACTION_MICROPHONE -> withMicrophone = true
        }
        show(signalRepo.calls().current())
        if (watching == null) {
            watching = signalRepo.calls().state()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ show(it) }, { Timber.w(it, "signal calls: service state") })
        }
        return START_NOT_STICKY
    }

    private fun show(state: SignalCallState) {
        when (state) {
            is SignalCallState.Ringing -> {
                foreground(ringingNotification(state.peer))
                if (ringer == null) {
                    ringer = IncomingRinger(this).also { it.start() }
                }
                holdScreenAwake()
            }
            is SignalCallState.Calling -> foreground(ongoingNotification(state.peer))
            is SignalCallState.Connecting -> {
                stopRinging()
                foreground(ongoingNotification(state.peer))
            }
            is SignalCallState.Connected -> {
                stopRinging()
                foreground(ongoingNotification(state.peer))
            }
            is SignalCallState.Ended, SignalCallState.Idle -> {
                stopRinging()
                if (!seenCall && withMicrophone) {
                    // Started for a call being placed, and the call has not said so yet: the
                    // screen starts this service a moment before the engine publishes it. It has
                    // to be in the foreground within seconds of starting all the same, so it
                    // waits there -- and gives up if no call appears.
                    foreground(ongoingNotification(""))
                    waiter.postDelayed({ if (!seenCall) finish() }, WAIT_FOR_CALL_MS)
                } else {
                    finish()
                }
            }
        }
        if (state !is SignalCallState.Idle && state !is SignalCallState.Ended) seenCall = true
    }

    private val waiter = android.os.Handler(android.os.Looper.getMainLooper())

    /** Whether this service has seen the call it was started for. */
    private var seenCall = false

    private fun foreground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = if (withMicrophone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            inForeground = true
        } catch (e: Exception) {
            // Android 12 refuses a foreground service started from the background unless the app
            // is already running one. The notification still goes up, with its full-screen
            // intent, so the call still shows over the lock screen -- it only rings when the
            // call screen is on top. Keeping Signal connected avoids this: that is a foreground
            // service already.
            Timber.w(e, "signal calls: could not become a foreground service")
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun ringingNotification(peer: String): Notification {
        val name = nameOf(this, peer)
        val screen = SignalCallActivity.intent(this)
        val fullScreen = PendingIntent.getActivity(
            this, 1, screen, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val answer = PendingIntent.getActivity(
            this, 2, SignalCallActivity.intent(this).setAction(SignalCallActivity.ACTION_ANSWER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val decline = PendingIntent.getService(
            this, 3, Intent(this, SignalCallService::class.java).setAction(ACTION_DECLINE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(name)
            .setContentText(getString(R.string.signal_call_incoming))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .addAction(0, getString(R.string.signal_call_decline), decline)
            .addAction(0, getString(R.string.signal_call_answer), answer)
            .build()
    }

    private fun ongoingNotification(peer: String): Notification {
        val screen = PendingIntent.getActivity(
            this, 1, SignalCallActivity.intent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val hangUp = PendingIntent.getService(
            this, 4, Intent(this, SignalCallService::class.java).setAction(ACTION_HANG_UP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(nameOf(this, peer))
            .setContentText(getString(R.string.signal_call_ongoing))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(screen)
            .addAction(0, getString(R.string.signal_call_hang_up), hangUp)
            .build()
    }

    /** Turns the screen on for the ringing call, as a phone call does, and lets it go after. */
    private fun holdScreenAwake() {
        if (wakeLock != null) return
        @Suppress("DEPRECATION")
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "kotozute:signal-call-ring"
            ).also { it.acquire(RING_WAKE_MS) }
    }

    private fun stopRinging() {
        ringer?.stop()
        ringer = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun finish() {
        waiter.removeCallbacksAndMessages(null)
        seenCall = false
        watching?.dispose()
        watching = null
        if (inForeground) stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        inForeground = false
        withMicrophone = false
        stopSelf()
    }

    override fun onDestroy() {
        stopRinging()
        watching?.dispose()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "signal_calls"
        private const val NOTIFICATION_ID = 7201
        private const val RING_WAKE_MS = 2 * 60 * 1000L
        private const val WAIT_FOR_CALL_MS = 10_000L

        const val ACTION_DECLINE = "com.wanderwildwood.kotozute.signalcall.DECLINE"
        const val ACTION_HANG_UP = "com.wanderwildwood.kotozute.signalcall.HANG_UP"
        const val ACTION_MICROPHONE = "com.wanderwildwood.kotozute.signalcall.MICROPHONE"

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, SignalCallService::class.java).setAction(action)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Timber.w(e, "signal calls: could not start the call service")
            }
        }

        /**
         * The calls channel: loud and on the lock screen. Silent itself, because the ringtone is
         * played by the service -- a channel sound plays once, and a call rings until it is
         * answered.
         */
        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.signal_call_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        /** The conversation's name for somebody, or plain "Signal" when there is none yet. */
        fun nameOf(context: Context, peer: String): String = runCatching {
            Realm.getDefaultInstance().use { realm ->
                realm.where(SignalThread::class.java)
                    .equalTo("threadKey", "direct:$peer")
                    .findFirst()?.title?.takeIf { it.isNotBlank() }
            }
        }.getOrNull() ?: context.getString(R.string.signal_title)
    }
}
