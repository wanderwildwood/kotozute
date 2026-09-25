package com.wanderwildwood.kotozute.feature.signal

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Whether Android is hiding Signal's notifications, and the screen that turns them back on.
 *
 * Upstream checks the same two things (`NotificationChannels.areNotificationsEnabled`,
 * `isMessageChannelEnabled`) and puts a "Turn on" banner at the top of its notification settings.
 * Here both are switched off in Android's settings, outside the app, and nothing said so: a
 * conversation that stops notifying looks exactly like messages that stopped arriving.
 */
object NotificationsOff {

    fun forApp(context: Context): Boolean =
        !NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun forSignal(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val channel = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .getNotificationChannel(SignalNotifications.CHANNEL_ID) ?: return false
        return channel.importance == NotificationManager.IMPORTANCE_NONE
    }

    fun any(context: Context): Boolean = runCatching { forApp(context) || forSignal(context) }.getOrDefault(false)

    /** The app's page when everything is off, Signal's own channel when only that is. */
    fun open(context: Context) {
        val intent = if (!forApp(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, SignalNotifications.CHANNEL_ID)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
