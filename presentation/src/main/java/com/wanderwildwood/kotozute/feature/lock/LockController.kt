package com.wanderwildwood.kotozute.feature.lock

import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import com.wanderwildwood.kotozute.lock.AppLock
import com.wanderwildwood.kotozute.lock.AppLock.lockAfterMinutes
import com.wanderwildwood.kotozute.lock.AppLock.wipeMemoryOnLock
import timber.log.Timber

/**
 * When the passphrase lock locks, and what locking does.
 *
 * Triggers (Molly's "lock after inactivity"): the screen turning off, or N minutes after the
 * last screen of this app was left. Chosen on the lock's settings screen.
 *
 * Locking, in order: every notification of this app is withdrawn (they carry names and
 * words), Signal, Desktop Sync and voice-note playback are stopped, the lock key is
 * overwritten, the memory wiper is queued in its own process when that is on, and this
 * process ends. A process that has ended holds no key; the next start finds the lock on and
 * asks for the passphrase.
 *
 * Never during a call: Android puts the phone into communication mode for one, and a lock
 * that hung up on somebody would be a lock nobody keeps on. It waits a minute and looks again.
 */
object LockController {

    private const val ACTION_IDLE = "com.wanderwildwood.kotozute.LOCK_IDLE"
    private const val RETRY_DURING_CALL_MS = 60_000L

    @Volatile private var watching = false
    private var started = 0

    /** Idempotent. Called at the end of a normal start, and when the lock is turned on. */
    fun watch(app: Application) {
        if (watching) return
        watching = true
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (AppLock.isOn(context) && context.lockAfterMinutes == AppLock.ON_SCREEN_OFF) {
                    lockNow(context, "screen off")
                }
            }
        }, IntentFilter(Intent.ACTION_SCREEN_OFF))
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                cancelIdle(app)
            }
            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0) scheduleIdle(app)
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun idleIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, IdleLockReceiver::class.java).setAction(ACTION_IDLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun scheduleIdle(context: Context, afterMs: Long? = null) {
        if (!AppLock.isOn(context)) return
        val minutes = context.lockAfterMinutes
        if (afterMs == null && minutes <= 0) return
        val delay = afterMs ?: (minutes * 60_000L)
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        // Inexact but allowed in Doze: a lock a little late beats a lock that never comes.
        alarms.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay, idleIntent(context)
        )
    }

    private fun cancelIdle(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(idleIntent(context))
    }

    private fun inCall(context: Context): Boolean {
        val mode = context.getSystemService(AudioManager::class.java)?.mode ?: return false
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE
    }

    /** Locks now. Ends this process; does not return when it locks. */
    fun lockNow(context: Context, why: String) {
        val app = context.applicationContext
        if (!AppLock.isOn(app) || AppLock.isLocked(app)) return
        if (inCall(app)) {
            Timber.i("lock: %s during a call; trying again in a minute", why)
            scheduleIdle(app, RETRY_DURING_CALL_MS)
            return
        }
        Timber.i("lock: locking (%s)", why)
        runCatching { NotificationManagerCompat.from(app).cancelAll() }
        for (service in listOf(
            com.wanderwildwood.kotozute.feature.signal.SignalStreamService::class.java,
            com.wanderwildwood.kotozute.feature.desktopsync.DesktopSyncService::class.java,
            com.wanderwildwood.kotozute.feature.signal.VoiceNotePlaybackService::class.java,
        )) {
            runCatching { app.stopService(Intent(app, service)) }
        }
        AppLock.forget()
        if (app.wipeMemoryOnLock) WipeMemoryJob.schedule(app)
        Process.killProcess(Process.myPid())
    }

    /** The idle timer. Declared in the manifest, not exported. */
    class IdleLockReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_IDLE) lockNow(context, "idle")
        }
    }
}
