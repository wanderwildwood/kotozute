package com.wanderwildwood.kotozute.feature.signal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.model.SignalMessage
import com.wanderwildwood.kotozute.feature.settings.SettingsActivity
import com.wanderwildwood.kotozute.repository.SignalRepository
import com.wanderwildwood.kotozute.util.Preferences
import io.realm.Realm
import com.wanderwildwood.kotozute.model.SignalThread
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts a notification when a Signal message arrives.
 *
 * Deliberately separate from NotificationManagerImpl: that one is telephony all the way
 * down -- keyed on a Long threadId, with mark-read and reply receivers built around the
 * SMS provider. Threading a second id type through it would complicate the path that
 * matters most for a messaging app to get right.
 */
@Singleton
class SignalNotifications @Inject constructor(
    private val context: Context,
    private val prefs: Preferences,
    private val signalRepo: SignalRepository
) {

    private val manager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private var started = false

    /** Idempotent: safe to call on every launch, and from anywhere that turns Signal on. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        createChannel()
        signalRepo.newIncoming().subscribe({ notify(it) }, { Timber.w(it, "signal notify") })

        // A notification carries the message text, so a message that has gone must take its
        // notification with it. Withdrawn, expired or deleted elsewhere, the row disappeared
        // from the app and the words stayed on the lock screen until the thread was next
        // opened -- which for a withdrawal is the opposite of what the sender asked for.
        //
        // The whole thread's notification goes, not one line of it: what is shown is built
        // from the message that arrived, and there is no record of which one is on screen.
        // Cancelling too much here costs a notification the reader would have seen anyway
        // when they opened the app; cancelling too little leaves text that was meant to be
        // gone.
        signalRepo.messagesRemoved()
            .subscribe({ cancel(it) }, { Timber.w(it, "signal notify: removal") })

        // Read on another of the account's devices. The message is still there and still
        // worth having; what is no longer true is that anybody needs telling about it, and a
        // notification for something already read is one the reader has to clear by hand --
        // by opening a conversation with nothing new in it.
        signalRepo.conversationsRead()
            .subscribe({ cancel(it) }, { Timber.w(it, "signal notify: read elsewhere") })
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // A channel's settings are frozen once created, so a change of behaviour needs a
        // new id rather than an edit to this one.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.signal_title),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    /**
     * Puts back what the system took away.
     *
     * Android drops an app's posted notifications when the app is updated and when the phone
     * restarts, and nothing else puts them back, so a message still unread after either had
     * no notification at all. Upstream re-posts them from `RestoreNotificationsReceiver` on
     * exactly those two broadcasts (`RestoreNotificationsJob`). The same here: the newest
     * unread message of each unmuted conversation, **silently** -- these were announced once
     * already.
     */
    fun restore() {
        if (!prefs.signalEnabled.get()) return
        createChannel()
        val pending = runCatching {
            Realm.getDefaultInstance().use { realm ->
                realm.where(SignalThread::class.java)
                    .greaterThan("unread", 0)
                    .equalTo("muted", false)
                    .findAll()
                    .mapNotNull { thread ->
                        realm.where(SignalMessage::class.java)
                            .equalTo("threadKey", thread.threadKey)
                            .equalTo("outgoing", false)
                            .equalTo("read", false)
                            .sort("date", io.realm.Sort.DESCENDING)
                            .findFirst()
                            ?.let { realm.copyFromRealm(it) }
                    }
            }
        }.onFailure { Timber.w(it, "signal notify: could not read what is unread") }.getOrNull() ?: return
        // ⚠ Not what the reader swiped away. Upstream's restore brings back only what was never
        // dismissed -- `DeleteNotificationReceiver` marks a swiped conversation notified -- and
        // re-posting everything unread turned a restart into the return of every notification
        // somebody had already chosen to be rid of.
        pending.filter { it.date > dismissedUpTo(context, it.threadKey) }
            .also { shown -> shown.forEach { notify(it, silent = true) } }
            .let { if (it.isNotEmpty()) Timber.i("signal notify: put back %d notification(s)", it.size) }
    }

    private fun notify(message: SignalMessage, silent: Boolean = false) {
        if (!prefs.signalEnabled.get()) return

        // Nothing to announce about a conversation the user is already reading.
        if (SignalThreadActivity.isVisible(message.threadKey)) return
        // Muted means no notification, not no message: it still arrives and still counts
        // as unread, exactly as muting an SMS conversation behaves.
        if (signalRepo.isMuted(message.threadKey)) return

        val title = titleFor(message.threadKey).ifBlank {
            message.senderNumber.ifBlank { context.getString(R.string.signal_title) }
        }
        val text = when {
            message.body.isNotBlank() -> message.body
            message.attachments.isNotBlank() -> context.getString(R.string.signal_attachment_image)
            else -> return
        }

        val intent = SignalConversationsActivity.intentFor(context, message.threadKey, title)
        val pending = PendingIntent.getActivity(
            context,
            message.threadKey.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .addAction(readAction(message.threadKey))
            .addAction(replyAction(message.threadKey))
            // Swiping it away is a decision about this message and the ones before it; see
            // [restore] and [SignalNotificationDismissedReceiver].
            .setDeleteIntent(
                PendingIntent.getBroadcast(
                    context,
                    message.threadKey.hashCode(),
                    Intent(context, SignalNotificationDismissedReceiver::class.java)
                        .putExtra(SignalNotificationDismissedReceiver.EXTRA_THREAD, message.threadKey)
                        .putExtra(SignalNotificationDismissedReceiver.EXTRA_UP_TO, message.date),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setSilent(silent)
            .build()

        manager.notify(idFor(message.threadKey), notification)
    }

    /** Reads the thread's own title so the notification says who, not a uuid. */
    private fun titleFor(threadKey: String): String = runCatching {
        Realm.getDefaultInstance().use { realm ->
            realm.where(SignalThread::class.java)
                .equalTo("threadKey", threadKey)
                .findFirst()
                ?.let {
                    com.wanderwildwood.kotozute.signal.SignalName.of(
                        name = it.title,
                        number = it.counterpartNumber,
                        serviceId = it.threadKey.substringAfter(":")
                    )
                }
                .orEmpty()
        }
    }.getOrDefault("")

    fun cancel(threadKey: String) = manager.cancel(idFor(threadKey))

    private fun actionIntent(threadKey: String, action: String, mutable: Boolean) =
        android.app.PendingIntent.getBroadcast(
            context,
            (action + threadKey).hashCode(),
            Intent(context, SignalNotificationActionReceiver::class.java)
                .setAction(action)
                .putExtra(SignalNotificationActionReceiver.EXTRA_THREAD, threadKey),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                // A reply's intent is filled in with what was typed, so it has to be mutable.
                if (mutable) android.app.PendingIntent.FLAG_MUTABLE else android.app.PendingIntent.FLAG_IMMUTABLE
        )

    private fun actionLabel(index: Int) =
        context.resources.getStringArray(R.array.notification_actions)[index]

    private fun readAction(threadKey: String) = NotificationCompat.Action.Builder(
        R.drawable.ic_check_white_24dp,
        actionLabel(Preferences.NOTIFICATION_ACTION_READ),
        actionIntent(threadKey, SignalNotificationActionReceiver.ACTION_READ, mutable = false)
    ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).build()

    private fun replyAction(threadKey: String): NotificationCompat.Action {
        val label = actionLabel(Preferences.NOTIFICATION_ACTION_REPLY)
        return NotificationCompat.Action.Builder(
            R.drawable.ic_reply_white_24dp,
            label,
            actionIntent(threadKey, SignalNotificationActionReceiver.ACTION_REPLY, mutable = true)
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .addRemoteInput(
                androidx.core.app.RemoteInput.Builder(SignalNotificationActionReceiver.KEY_REPLY)
                    .setLabel(label)
                    .build()
            )
            .build()
    }

    /** Replaces the notification with one saying the reply from it did not go. */
    fun replyFailed(threadKey: String) {
        val title = titleFor(threadKey).ifBlank { context.getString(R.string.signal_title) }
        val intent = SignalConversationsActivity.intentFor(context, threadKey, title)
        manager.notify(
            idFor(threadKey),
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(context.getString(R.string.signal_notification_reply_failed))
                .setContentIntent(
                    android.app.PendingIntent.getActivity(
                        context, threadKey.hashCode(), intent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .setAutoCancel(true)
                .setSilent(true)
                .build()
        )
    }

    private fun idFor(threadKey: String) = NOTIFICATION_ID_BASE + threadKey.hashCode()

    companion object {
        const val CHANNEL_ID = "notifications_signal"
        private const val NOTIFICATION_ID_BASE = 0x5167 // keeps clear of the SMS ids
    }

}

/** How far each conversation's notifications were swiped away: the newest message dismissed. */
private const val DISMISSED_PREFS = "signal-notifications-dismissed"

internal fun dismissedUpTo(context: Context, threadKey: String): Long =
    context.getSharedPreferences(DISMISSED_PREFS, Context.MODE_PRIVATE).getLong(threadKey, 0L)

internal fun recordDismissed(context: Context, threadKey: String, upTo: Long) {
    val prefs = context.getSharedPreferences(DISMISSED_PREFS, Context.MODE_PRIVATE)
    if (upTo > prefs.getLong(threadKey, 0L)) prefs.edit().putLong(threadKey, upTo).apply()
}
