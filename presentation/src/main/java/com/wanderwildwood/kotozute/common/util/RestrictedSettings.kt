package com.wanderwildwood.kotozute.common.util

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.wanderwildwood.kotozute.R

/**
 * Android's "restricted settings": from Android 13 an app installed from a file may not be given
 * some settings until the person allows it on the app's info page (⋮ → Allow restricted
 * settings), and from Android 15 that includes becoming the default texting app. Android refuses
 * without saying how to get past it, so this says it, but only after a request has come back
 * refused and the restriction is in force. On MuditaOS (Android 12) there is no such thing and
 * this never shows.
 */
object RestrictedSettings {

    private const val OP = "android:access_restricted_settings"

    /** True only where the restriction exists and has not been lifted for this app. */
    fun isRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return runCatching {
            val ops = context.getSystemService(AppOpsManager::class.java)
            // Errored is what Android sets for an app installed from a file; allowing restricted
            // settings sets it to allowed. Anything else is not this restriction.
            val mode = ops.unsafeCheckOpNoThrow(OP, context.applicationInfo.uid, context.packageName)
            mode == AppOpsManager.MODE_ERRORED || mode == AppOpsManager.MODE_IGNORED
        }.getOrDefault(false)
    }

    fun showHint(activity: Activity) {
        activity.einkDialog()
            .setMessage(R.string.restricted_settings_hint)
            .setPositiveButton(R.string.restricted_settings_app_info) { _, _ -> openAppInfo(activity) }
            .setNegativeButton(R.string.button_close, null)
            .show()
    }

    private fun openAppInfo(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            )
        }
    }
}
