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

    override fun onReceive(context: Context, intent: Intent?) {
        AndroidInjection.inject(this, context)
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
