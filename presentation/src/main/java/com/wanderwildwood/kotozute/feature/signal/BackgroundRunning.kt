package com.wanderwildwood.kotozute.feature.signal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Whether Android lets Messaging keep running to save battery, and the way to ask.
 *
 * Signal, on a phone without push, asks once to be left out of battery optimisation
 * (`DozeBanner`, `PowerManagerCompat.requestIgnoreBatteryOptimizations`), because otherwise the
 * system defers the only thing that brings messages in. This app is always without push. The ask
 * is Android's own dialog; on a Kompakt, DuraSpeed is a separate list the dialog does not cover.
 */
object BackgroundRunning {

    fun isAllowed(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(true)

    /**
     * The one-time ask, from whichever screen the app opens on -- the text list, or the Signal
     * list for somebody who set "Open on Signal". After it, the row in Signal settings.
     */
    fun askOnce(context: Context, prefs: com.wanderwildwood.kotozute.util.Preferences) {
        runCatching {
            if (prefs.signalEnabled.get() && !prefs.signalAskedBackground.get() && !isAllowed(context)) {
                prefs.signalAskedBackground.set(true)
                ask(context)
            }
        }
    }

    /** Android's "let this app run in the background?" dialog, or its list where that is refused. */
    fun ask(context: Context) {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(direct) }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
