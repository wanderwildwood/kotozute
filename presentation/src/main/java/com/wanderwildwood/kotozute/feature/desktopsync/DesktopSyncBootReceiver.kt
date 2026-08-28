/*
 * Brings the Desktop Sync relay back after a reboot, so the bookmarked desktop
 * URL keeps working without having to open the app and re-toggle anything.
 *
 * The existing BootReceiver lives in the :data module and can't see this service,
 * hence a separate receiver here in :presentation.
 */
package com.wanderwildwood.kotozute.feature.desktopsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wanderwildwood.kotozute.util.Preferences
import dagger.android.AndroidInjection
import javax.inject.Inject

class DesktopSyncBootReceiver : BroadcastReceiver() {

    @Inject lateinit var prefs: Preferences

    override fun onReceive(context: Context, intent: Intent?) {
        AndroidInjection.inject(this, context)
        DesktopSyncService.restoreIfEnabled(context, prefs)
    }
}
