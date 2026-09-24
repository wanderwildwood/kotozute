package com.wanderwildwood.kotozute.feature.signal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.android.AndroidInjection
import javax.inject.Inject
import kotlin.concurrent.thread

/**
 * After a restart or an update, puts back the Signal notifications the system dropped.
 * See [SignalNotifications.restore]; upstream's `RestoreNotificationsReceiver`.
 */
class SignalRestoreNotificationsReceiver : BroadcastReceiver() {

    @Inject lateinit var notifications: SignalNotifications
    @Inject lateinit var prefs: com.wanderwildwood.kotozute.util.Preferences

    override fun onReceive(context: Context, intent: Intent?) {
        AndroidInjection.inject(this, context)
        // And catch up straight away after a restart, as upstream's `BootReceiver` does,
        // rather than at the next periodic run.
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && prefs.signalEnabled.get()) {
            com.wanderwildwood.kotozute.worker.SignalSyncWorker.now(context)
        }
        val result = goAsync()
        // Realm and the notification manager both, off the main thread.
        thread(isDaemon = true) {
            try {
                notifications.restore()
            } finally {
                result.finish()
            }
        }
    }
}
