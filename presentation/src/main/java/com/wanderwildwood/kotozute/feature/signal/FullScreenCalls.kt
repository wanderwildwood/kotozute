package com.wanderwildwood.kotozute.feature.signal

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Whether a Signal call may wake the screen and fill it, and the way to allow it.
 *
 * From Android 14 the full-screen intent a ringing call uses is a permission the person grants,
 * and an app installed from a file starts without it: the call then rings only as a notification,
 * with the screen asleep. On Android 12 and 13 (MuditaOS) it is granted at install, and this
 * always says yes.
 */
object FullScreenCalls {

    fun isAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .canUseFullScreenIntent()
        }.getOrDefault(true)
    }

    /** Android's page for this app's "full screen notifications" switch. */
    fun open(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
