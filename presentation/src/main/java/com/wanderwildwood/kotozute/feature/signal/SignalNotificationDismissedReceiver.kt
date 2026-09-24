package com.wanderwildwood.kotozute.feature.signal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * A Signal notification was swiped away: remember that, so a restore does not bring it back.
 * Upstream's `DeleteNotificationReceiver`, which marks the conversation's messages notified.
 */
class SignalNotificationDismissedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val threadKey = intent?.getStringExtra(EXTRA_THREAD) ?: return
        val upTo = intent.getLongExtra(EXTRA_UP_TO, 0L).takeIf { it > 0 } ?: return
        recordDismissed(context, threadKey, upTo)
    }

    companion object {
        const val EXTRA_THREAD = "threadKey"
        const val EXTRA_UP_TO = "upTo"
    }
}
