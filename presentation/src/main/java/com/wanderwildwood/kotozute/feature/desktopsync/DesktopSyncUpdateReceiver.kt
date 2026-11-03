package com.wanderwildwood.kotozute.feature.desktopsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wanderwildwood.kotozute.common.QKApplication

/**
 * After the app is updated: Desktop Sync starts again if it was on, and the phone has not
 * restarted since it was switched on. Android lets this broadcast start a foreground service from
 * the background. There is deliberately no boot receiver for Desktop Sync: a reboot leaves it off.
 */
class DesktopSyncUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as? QKApplication ?: return
        DesktopSyncService.resumeIfOn(context.applicationContext, app.prefs)
    }
}
