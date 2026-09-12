package com.wanderwildwood.kotozute.signalstore

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import androidx.core.app.AlarmManagerCompat
import androidx.core.content.ContextCompat
import org.signal.core.util.SleepTimer
import timber.log.Timber
import java.util.concurrent.ConcurrentSkipListSet

/**
 * A sleep that survives Doze.
 *
 * Ported from Signal's `AlarmSleepTimer`, and the reason it exists is worth stating plainly:
 * `UptimeSleepTimer` -- what this app used everywhere -- sleeps a thread, and Android stops
 * running threads when the device dozes. Every timing that mattered was measured with it. The
 * keepalive round is "send at 30 seconds, check at 50", and under Doze those thirty seconds
 * become however long the device felt like sleeping. The websocket's own read timeout is set
 * the same way. So on a phone built to spend most of its life asleep -- which is exactly what
 * the Kompakt is -- a connection could be dead for an hour with nothing noticing, and the
 * check written to catch that was itself asleep.
 *
 * An alarm is the one thing Android will honour in that state. `ELAPSED_REALTIME_WAKEUP` plus
 * `setExactAndAllowWhileIdle` wakes the device, which is a cost paid deliberately: this is the
 * timer that decides whether messages arrive.
 *
 * Signal uses this only when it has no push and must hold the socket itself. That is this app
 * always: there is no FCM here, and the socket is the only way anything arrives.
 */
internal class AlarmSleepTimer(private val context: Context) : SleepTimer {

    override fun sleep(sleepDuration: Long) {
        val receiver = AlarmReceiver()
        var actionId = 0
        // Two sleeps at once are ordinary -- the keepalive thread and the socket's own read
        // timeout -- and a shared action name would have either alarm wake both.
        while (!inFlight.add(actionId)) actionId++

        try {
            val actionName = "$WAKE_UP_ACTION.$actionId"
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(actionName), ContextCompat.RECEIVER_NOT_EXPORTED
            )

            val startTime = System.currentTimeMillis()
            setAlarm(sleepDuration, actionName)

            // Waits for the alarm, but re-checks the wall clock: a spurious wake-up must not
            // be allowed to cut the sleep short, or the keepalive would fire early and the
            // check that follows it would run before any answer could have arrived.
            while (System.currentTimeMillis() - startTime < sleepDuration) {
                try {
                    synchronized(this) {
                        // At least 1. wait(0) is "wait for ever", and the remaining time can
                        // reach zero between the loop's check and this line -- which would
                        // park the keepalive thread permanently on a sleep that had just
                        // finished.
                        val remaining = sleepDuration - (System.currentTimeMillis() - startTime)
                        (this as Object).wait(remaining.coerceAtLeast(1))
                    }
                } catch (e: InterruptedException) {
                    Timber.w(e, "signal timer: interrupted while sleeping")
                }
            }
            context.unregisterReceiver(receiver)
        } catch (t: Throwable) {
            // Never fatal. A sleep that fails degrades to the behaviour this replaced; a sleep
            // that throws would take the keepalive thread with it.
            Timber.w(t, "signal timer: could not sleep on an alarm")
        } finally {
            inFlight.remove(actionId)
        }
    }

    private fun setAlarm(millis: Long, action: String) {
        val intent = Intent(action).setPackage(context.packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // From Android 12 an exact alarm is a permission, and this app does not ask for one:
        // it is granted to calendars and alarm clocks, not to us. The inexact alarm still
        // fires during Doze, just later than asked -- which is worse than exact and much
        // better than a thread that is not running at all.
        if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
            AlarmManagerCompat.setExactAndAllowWhileIdle(
                alarmManager,
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + millis,
                pendingIntent
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + millis,
                pendingIntent
            )
        }
    }

    private inner class AlarmReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            synchronized(this@AlarmSleepTimer) {
                (this@AlarmSleepTimer as Object).notifyAll()
            }
        }
    }

    private companion object {
        private const val WAKE_UP_ACTION =
            "com.wanderwildwood.kotozute.signalstore.AlarmSleepTimer.WAKE_UP"

        /** Action ids in use, so two concurrent sleeps cannot share an alarm. */
        private val inFlight = ConcurrentSkipListSet<Int>()
    }
}
