package com.wanderwildwood.kotozute.feature.desktopsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wanderwildwood.kotozute.common.QKApplication

/**
 * After the phone starts up, and after the app is updated: Desktop Sync starts again if it was
 * left on. Android 12 lets both broadcasts start a foreground service from the background, which
 * a plain process start in the background may not do.
 */
class DesktopSyncRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        val app = context.applicationContext as? QKApplication ?: return
        DesktopSyncService.resumeIfOn(context.applicationContext, app.prefs)
    }
}
