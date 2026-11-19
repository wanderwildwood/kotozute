package com.wanderwildwood.kotozute.lock

import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.wanderwildwood.kotozute.repository.SyncRepository
import com.wanderwildwood.kotozute.util.MessageStoreGate
import timber.log.Timber

/**
 * Texts that arrive while the message database is closed -- the passphrase lock, or a keystore
 * that will not answer.
 *
 * SMS and MMS are still stored: Android's own provider gets them exactly as before, because
 * the default SMS app is the only one that writes them there and a text not written is a text
 * lost. What waits is the copy in this app's database. Their provider addresses are kept here
 * (`content://sms/123` says nothing about who or what) and brought in at unlock, through the
 * same sync a normal start uses.
 *
 * The notification says only that something came. Who and what would put the locked messages
 * on the lock screen, which is the thing the lock is for.
 */
object LockedArrivals {

    private const val PREFS = "locked_arrivals"
    private const val PREF_URIS = "uris"
    private const val NOTIFICATION_ID = 0x10C4ED
    // The app's ordinary message channel (NotificationManagerImpl.DEFAULT_CHANNEL_ID). It
    // exists already: the lock can only be turned on from a normal start, which creates it.
    private const val CHANNEL_ID = "notifications_default"

    fun waiting(): Boolean = MessageStoreGate.blocked

    @Synchronized
    fun record(context: Context, uri: Uri) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = p.getStringSet(PREF_URIS, emptySet()).orEmpty() + uri.toString()
        p.edit().putStringSet(PREF_URIS, all).commit()
        notifyWaiting(context, all.size)
    }

    private fun notifyWaiting(context: Context, count: Int) {
        val res = context.resources
        val icon = res.getIdentifier("ic_notification", "drawable", context.packageName)
            .takeIf { it != 0 } ?: android.R.drawable.stat_notify_chat
        val titleId = res.getIdentifier("lock_arrivals_title", "plurals", context.packageName)
        val title = if (titleId != 0) res.getQuantityString(titleId, count, count) else "$count"
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val open = launch?.let {
            android.app.PendingIntent.getActivity(
                context, NOTIFICATION_ID, it,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n) }
            .onFailure { Timber.w(it, "lock: could not post the waiting-messages notice") }
    }

    /** At unlock: copy the waiting texts in from the provider, then clear the list and notice. */
    @Synchronized
    fun bringIn(context: Context, syncRepo: SyncRepository) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uris = p.getStringSet(PREF_URIS, emptySet()).orEmpty()
        var done = 0
        for (u in uris) {
            runCatching { syncRepo.syncMessage(Uri.parse(u)) }
                .onSuccess { done++ }
                .onFailure { Timber.w(it, "lock: could not bring in a text that arrived while locked") }
        }
        p.edit().remove(PREF_URIS).commit()
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        if (uris.isNotEmpty()) Timber.i("lock: brought in %d of %d text(s) that arrived while locked", done, uris.size)
    }
}
