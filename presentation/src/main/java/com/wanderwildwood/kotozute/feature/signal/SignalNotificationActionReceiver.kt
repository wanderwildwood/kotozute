package com.wanderwildwood.kotozute.feature.signal

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.wanderwildwood.kotozute.repository.SignalRepository
import dagger.android.AndroidInjection
import timber.log.Timber
import javax.inject.Inject
import kotlin.concurrent.thread

/**
 * "Mark read" and "Reply" on a Signal notification.
 *
 * Upstream offers both on every message notification (`MarkReadReceiver`,
 * `RemoteReplyReceiver`), and this app's own text notifications always had them. A reply marks
 * the conversation read, as upstream's does: somebody answering has read what they answer.
 */
class SignalNotificationActionReceiver : BroadcastReceiver() {

    @Inject lateinit var signalRepo: SignalRepository
    @Inject lateinit var notifications: SignalNotifications

    override fun onReceive(context: Context, intent: Intent) {
        AndroidInjection.inject(this, context)
        val threadKey = intent.getStringExtra(EXTRA_THREAD) ?: return
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)
            ?.toString()?.trim()
        val result = goAsync()
        thread(isDaemon = true) {
            try {
                if (intent.action == ACTION_REPLY) {
                    if (reply.isNullOrEmpty()) return@thread
                    val sent = runCatching { signalRepo.send(threadKey, reply) }
                        .onFailure { Timber.w(it, "signal notify: a reply from the notification did not go") }
                        .isSuccess
                    if (!sent) {
                        // Said where it was typed. A notification that simply went away would
                        // read as a reply that had gone.
                        notifications.replyFailed(threadKey)
                        return@thread
                    }
                }
                runCatching { signalRepo.markRead(threadKey, System.currentTimeMillis()) }
                    .onFailure { Timber.w(it, "signal notify: could not mark read") }
                notifications.cancel(threadKey)
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        const val ACTION_READ = "com.wanderwildwood.kotozute.signal.NOTIFICATION_READ"
        const val ACTION_REPLY = "com.wanderwildwood.kotozute.signal.NOTIFICATION_REPLY"
        const val EXTRA_THREAD = "threadKey"
        const val KEY_REPLY = "body"
    }
}
