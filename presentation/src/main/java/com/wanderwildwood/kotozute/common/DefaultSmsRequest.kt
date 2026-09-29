package com.wanderwildwood.kotozute.common

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.provider.Telephony
import timber.log.Timber

/**
 * What happens after Android has been asked to make this the texting app.
 *
 * Once somebody has said no to that request and ticked "Don't ask again", Android refuses every
 * later request itself, at once and with nothing on screen. Change on the texting line then
 * did nothing at all (forum report, 2026-09-29). There is no call that says the request is
 * blocked, so the answer is read from how fast it came: a person reading the dialog and saying
 * no takes seconds, and a refusal nobody saw is back before a screen could have drawn. That
 * one goes on to the phone's own Default apps page, where the choice can still be made.
 */
object DefaultSmsRequest {

    const val REQUEST_CODE = 42389

    /** Longer than any refusal Android makes itself, shorter than anybody reading a dialog. */
    private const val UNSEEN_MS = 800L

    private var askedAt = 0L

    /**
     * [tapped] false for an ask the app makes on its own, as the SMS list does when it opens.
     * Its refusal is left at that: sending somebody to a settings page every time the app
     * opened, for a question they had already answered "Don't ask again" to, is worse than
     * the silence this exists to fix.
     */
    fun asking(tapped: Boolean) {
        askedAt = if (tapped) SystemClock.elapsedRealtime() else 0L
    }

    fun answered(activity: Activity, resultCode: Int) {
        val at = askedAt
        askedAt = 0L
        if (at == 0L || resultCode == Activity.RESULT_OK) return
        if (SystemClock.elapsedRealtime() - at > UNSEEN_MS) return
        if (Telephony.Sms.getDefaultSmsPackage(activity) == activity.packageName) return
        try {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No Default apps page; leaving the default SMS app alone")
        }
    }
}
