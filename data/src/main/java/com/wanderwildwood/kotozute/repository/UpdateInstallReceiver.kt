package com.wanderwildwood.kotozute.repository

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import timber.log.Timber

/**
 * What the system says about an install we asked for.
 *
 * The one case here that does work rather than reporting it is PENDING_USER_ACTION. Android
 * only lets an app replace itself unasked when it was the one that installed itself in the
 * first place; almost nobody here is in that position, because Obtainium or a browser did the
 * original install. For everyone else the session commits, this arrives, and the system's own
 * install screen has to be started from the intent it hands over -- without that step the
 * update is sitting in a committed session that nothing will ever show anyone.
 *
 * Nothing is reported to the person from here. This only runs after they asked for the update
 * in settings and are still looking at the screen, so the system's install prompt is the next
 * thing they see, and a notification saying the same thing would be a second copy of it.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (val status = intent?.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) ?: -1) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val prompt: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (prompt == null) {
                    Timber.w("Install needs the person to agree, but no prompt came with it")
                    return
                }
                // A receiver has no task of its own to start an activity into.
                prompt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(prompt) }
                    .onFailure {
                        // The download is already on disk and verified; only the prompt
                        // failed. Nothing is installed without it, which is the safe
                        // direction, and the update is offered again on the next check
                        // rather than being lost.
                        Timber.w(it, "Could not show the install prompt; the next check offers it again")
                    }
            }

            // Nothing follows this: the process is about to be replaced by the new build.
            PackageInstaller.STATUS_SUCCESS -> Timber.i("Update installed")

            else -> Timber.w(
                "Update install did not happen: status $status, %s",
                intent?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            )
        }
    }

}
