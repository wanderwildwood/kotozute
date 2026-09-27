package com.wanderwildwood.kotozute.feature.setup

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.feature.signal.SignalWording
import com.wanderwildwood.kotozute.feature.signal.say
import com.wanderwildwood.kotozute.repository.SignalRepository
import com.wanderwildwood.kotozute.util.Preferences
import kotlin.concurrent.thread

/**
 * When the setup guide is offered: once, unasked.
 *
 * A new install is taken through the whole guide. Somebody who has been using the app is not:
 * they have made these choices already, and a guide that asked them again would be undoing
 * their answers for the sake of a first run they are past. They are asked only the one new
 * thing, pairing texts with Signal, and only if Signal is on this phone.
 *
 * Marked seen before anything is shown, so a guide left half-way is not put in front of anyone
 * a second time; it is in Settings for whoever wants it.
 */
object SetupGuide {

    fun offer(activity: Activity, prefs: Preferences, signalRepo: SignalRepository) {
        if (prefs.setupSeen.get()) return
        prefs.setupSeen.set(true)
        val info = runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0) }.getOrNull() ?: return
        if (info.firstInstallTime == info.lastUpdateTime) {
            activity.startActivity(SetupActivity.intent(activity))
            return
        }
        val signalOn = runCatching { signalRepo.connectionState().blockingFirst().configured }.getOrDefault(false)
        if (!signalOn) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.setup_pair_title)
            .setMessage(R.string.setup_pair_body)
            .setPositiveButton(R.string.setup_pair_yes) { _, _ ->
                thread(isDaemon = true) {
                    val words = runCatching { activity.say(SignalWording.contacts(signalRepo.discoverContactsByNumber())) }
                        .getOrElse { activity.getString(R.string.settings_signal_discover_contacts_failed) }
                    activity.runOnUiThread { Toast.makeText(activity, words, Toast.LENGTH_LONG).show() }
                }
            }
            .setNegativeButton(R.string.setup_pair_no, null)
            .show()
    }
}
