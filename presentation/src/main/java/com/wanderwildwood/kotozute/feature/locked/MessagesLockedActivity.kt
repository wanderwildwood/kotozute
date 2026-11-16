package com.wanderwildwood.kotozute.feature.locked

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.util.RealmEncryption
import timber.log.Timber

/**
 * The one screen the app shows when it has refused to open its message database: the phone's
 * keystore would not give it the key, and the app does not keep messages without one. See
 * [RealmEncryption.refusal] for when that is, and `QKApplication.refuseMessageStore` for what
 * the rest of the app does meanwhile.
 *
 * Deliberately plain: a framework Activity with framework widgets, no injection, no database,
 * nothing from the rest of the app. It has to work exactly when the rest of the app cannot.
 *
 * Two things can be done from here, and the screen says which applies:
 *
 *  - **Try again** restarts the app, which asks the keystore again. A keystore is sometimes
 *    only unavailable for a while (after a system update, or early after a restart).
 *  - **Start with an empty list**, offered only when there is an encrypted database whose key
 *    is missing, and only behind a second question. The old database is moved aside on the
 *    phone, not deleted, and a new key is made on the next start.
 */
class MessagesLockedActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.messages_locked_activity)

        val refusal = runCatching {
            RealmEncryption.Refusal.valueOf(intent.getStringExtra(EXTRA_REFUSAL).orEmpty())
        }.getOrDefault(RealmEncryption.Refusal.NO_KEY_FOR_NEW)
        val hasOldDatabase = refusal == RealmEncryption.Refusal.NO_KEY_FOR_ENCRYPTED

        findViewById<TextView>(R.id.lockedBody).setText(
            if (hasOldDatabase) R.string.messages_locked_body_existing
            else R.string.messages_locked_body_new
        )
        findViewById<Button>(R.id.lockedRetry).setOnClickListener { restart() }
        findViewById<Button>(R.id.lockedStartEmpty).apply {
            visibility = if (hasOldDatabase) View.VISIBLE else View.GONE
            setOnClickListener { confirmStartEmpty() }
        }
    }

    private fun confirmStartEmpty() {
        AlertDialog.Builder(this)
            .setTitle(R.string.messages_locked_start_empty_title)
            .setMessage(R.string.messages_locked_start_empty_message)
            .setNegativeButton(R.string.button_cancel, null)
            .setPositiveButton(R.string.messages_locked_start_empty_confirm) { _, _ ->
                val moved = runCatching {
                    RealmEncryption.setAsideUnreadableRealm(applicationContext, forgetKey = true)
                }.onFailure { Timber.e(it, "realm: could not set the locked database aside") }
                    .getOrDefault(false)
                if (moved) {
                    restart()
                } else {
                    AlertDialog.Builder(this)
                        .setMessage(R.string.messages_locked_start_empty_failed)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            .show()
    }

    /**
     * Ends this process and opens the app again in a new one. Everything that decides whether
     * the database opens happens once, as the application starts, so asking again means
     * starting again.
     */
    private fun restart() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (launch != null) runCatching { startActivity(launch) }
        finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    companion object {
        private const val EXTRA_REFUSAL = "refusal"

        fun intent(context: Context, refusal: RealmEncryption.Refusal): Intent =
            Intent(context, MessagesLockedActivity::class.java)
                .putExtra(EXTRA_REFUSAL, refusal.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
