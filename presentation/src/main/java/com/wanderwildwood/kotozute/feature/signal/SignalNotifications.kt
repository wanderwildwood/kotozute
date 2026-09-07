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
        watchForRefusal()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // A channel's settings are frozen once created, so a change of behaviour needs a
        // new id rather than an edit to this one.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Signal", NotificationManager.IMPORTANCE_HIGH)
        )
        // Its own channel, so it can be silenced without silencing messages -- and at
        // DEFAULT rather than HIGH: this needs to be seen once, not to interrupt.
        manager.createNotificationChannel(
            NotificationChannel(
                PROBLEM_CHANNEL_ID,
                context.getString(R.string.signal_rejected_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    /**
     * Says so when the bridge refuses this phone.
     *
     * Until this, a rotated token or a revoked pairing left Signal simply silent: the
     * catch-up worker swallowed the failure as ordinary, the stream retried on a backoff
     * forever, and nothing anywhere said why messages had stopped. Only a refusal is
     * announced -- a host that is switched off says nothing, because it comes back on its
     * own and a nightly notification about a laptop being asleep is noise.
     */
    private fun watchForRefusal() {
        signalRepo.connectionState()
            .map { it.enabled && it.rejected }
            .distinctUntilChanged()
            .subscribe({ refused ->
                when {
                    refused -> notifyRefused()
                    else -> manager.cancel(REFUSED_NOTIFICATION_ID)
                }
            }, { Timber.w(it, "signal connection state") })
    }

    private fun notifyRefused() {
        val pending = PendingIntent.getActivity(
            context,
            REFUSED_NOTIFICATION_ID,
            Intent(context, SettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = context.getString(R.string.signal_rejected_text)
        manager.notify(
            REFUSED_NOTIFICATION_ID,
            NotificationCompat.Builder(context, PROBLEM_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.signal_rejected_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                // Not auto-cancelling and not ongoing: it goes when the bridge accepts us
                // again, not when it is swiped or tapped, because tapping it does not fix
                // anything by itself.
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_ERROR)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pending)
                .build()
        )
    }

    private fun notify(message: SignalMessage) {
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
            .build()

        manager.notify(idFor(message.threadKey), notification)
    }

    /** Reads the thread's own title so the notification says who, not a uuid. */
    private fun titleFor(threadKey: String): String = runCatching {
        Realm.getDefaultInstance().use { realm ->
            realm.where(SignalThread::class.java)
                .equalTo("threadKey", threadKey)
                .findFirst()
                ?.let { it.title.ifBlank { it.counterpartNumber } }
                .orEmpty()
        }
    }.getOrDefault("")

    fun cancel(threadKey: String) = manager.cancel(idFor(threadKey))

    private fun idFor(threadKey: String) = NOTIFICATION_ID_BASE + threadKey.hashCode()

    companion object {
        const val CHANNEL_ID = "notifications_signal"
        const val PROBLEM_CHANNEL_ID = "notifications_signal_problem"
        private const val NOTIFICATION_ID_BASE = 0x5167 // keeps clear of the SMS ids
        private const val REFUSED_NOTIFICATION_ID = 0x5166
    }
}
