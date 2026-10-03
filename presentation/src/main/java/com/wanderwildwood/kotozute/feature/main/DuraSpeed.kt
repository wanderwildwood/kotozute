package com.wanderwildwood.kotozute.feature.main

import android.app.Activity
import android.app.ActivityManager
import androidx.appcompat.app.AlertDialog
import android.app.ApplicationExitInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import android.text.format.DateUtils
import androidx.core.app.NotificationCompat
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.util.Preferences
import com.wanderwildwood.kotozute.common.util.einkDialog

/**
 * DuraSpeed, MediaTek's background manager on the Kompakt, force-stops apps a few minutes after
 * the screen goes dark -- foreground services and all -- and then will not let a text start the
 * texting app again. Android discards its own copy once the hand-over fails, so the text is lost,
 * not late. Mudita's own Messages is on DuraSpeed's built-in list; this app is not.
 *
 * Keeping the process alive does not help: while the app is on DuraSpeed's suppress list the text
 * is skipped even if it is running (measured 2026-10-01, broadcast history "Skipped"), and only
 * opening the app takes it off. What ends it is Messaging switched on in DuraSpeed's own list, and
 * then a text starts the app as usual. On there means allowed.
 *
 * Settings on a Kompakt has no way into DuraSpeed: no menu entry, no search. Its screen will not
 * open for another app either, but its App info page will, and that page has an Open button.
 * Android records the stop as "stop <package> due to from pid N", unlike an update or a crash,
 * which is how the app can say afterwards that it happened. A force-stop from Settings reads the
 * same; DuraSpeed's mark on the app (restricted in the background) would tell them apart, but
 * opening the app lifts it before anything here can look (measured 2026-10-01). What is left is
 * DuraSpeed's own switch: a stop while it is on is taken as DuraSpeed's, since it does not stop
 * apps on its list at all. A Force stop by hand with DuraSpeed on is the one it gets wrong.
 *
 * Whether Messaging is on DuraSpeed's list cannot be read either, so until the person says it is,
 * a notification stays up; a stop by DuraSpeed afterwards takes their word back. Mudita's panel
 * shows a notification without its buttons, so tapping it opens the list here, where both choices
 * are, and coming back from DuraSpeed asks whether it was done.
 */
object DuraSpeed {

    fun isKompakt(): Boolean = Build.MANUFACTURER.equals("Mudita", ignoreCase = true)

    /** The newest force-stop by the system since [after], or null. Android 11 and later. */
    fun lastStop(context: Context, after: Long): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 0) }
            .getOrDefault(emptyList())
            .filter {
                it.reason == ApplicationExitInfo.REASON_USER_REQUESTED &&
                    it.description?.contains("due to from pid") == true &&
                    it.timestamp > after
            }
            .maxOfOrNull { it.timestamp }
    }

    private const val CHANNEL = "duraspeed"
    private const val NOTIFICATION_ID = 20261001
    private const val EXTRA_FIX = "duraspeedFix"

    /** Sent to DuraSpeed from here; the next return asks whether Messaging was switched on. */
    private var askOnReturn = false

    fun isFixIntent(intent: Intent) = intent.getBooleanExtra(EXTRA_FIX, false)

    private fun isTextingApp(context: Context) =
        Telephony.Sms.getDefaultSmsPackage(context) == context.packageName

    /** DuraSpeed's own switch, where the phone lets an app read it; null where it does not say. */
    fun isOn(context: Context): Boolean? = runCatching {
        val cr = context.contentResolver
        (Settings.Global.getString(cr, "setting.duraspeed.enabled")
            ?: Settings.System.getString(cr, "setting.duraspeed.enabled"))?.let { it != "0" }
    }.getOrNull()

    fun needsAllowing(context: Context, prefs: Preferences): Boolean =
        isKompakt() && isTextingApp(context) && isOn(context) != false && !prefs.duraSpeedAllowed.get()

    fun markAllowed(context: Context, prefs: Preferences) {
        prefs.duraSpeedAllowed.set(true)
        refreshNotification(context, prefs)
    }

    /** Up while Messaging may still be on DuraSpeed's restricted side; gone once it is not. */
    fun refreshNotification(context: Context, prefs: Preferences) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!needsAllowing(context, prefs)) {
            nm.cancel(NOTIFICATION_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL,
                context.getString(R.string.duraspeed_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val immutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val open = PendingIntent.getActivity(context, NOTIFICATION_ID, appInfo(), immutable)
        val fix = PendingIntent.getActivity(context, NOTIFICATION_ID + 1,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_FIX, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), immutable)
        val allowed = PendingIntent.getBroadcast(context, NOTIFICATION_ID,
            Intent(context, DuraSpeedAllowedReceiver::class.java), immutable)
        val text = context.getString(R.string.duraspeed_notification_text)
        nm.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.duraspeed_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(fix)
            .addAction(0, context.getString(R.string.duraspeed_open), open)
            .addAction(0, context.getString(R.string.duraspeed_allowed), allowed)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build())
    }

    /**
     * Said once for each stop, on the next visit to the list: only while this is the texting app,
     * since otherwise the texts went to whichever app is.
     */
    fun warnIfStopped(activity: Activity, prefs: Preferences) {
        if (!isKompakt() || !isTextingApp(activity)) return
        refreshNotification(activity, prefs)
        if (isFixIntent(activity.intent)) {
            activity.intent.removeExtra(EXTRA_FIX)
            showFix(activity, prefs)
            return
        }
        if (askOnReturn) {
            askOnReturn = false
            if (needsAllowing(activity, prefs)) {
                activity.einkDialog()
                    .setMessage(R.string.duraspeed_ask_done)
                    .setPositiveButton(R.string.duraspeed_allowed) { _, _ -> markAllowed(activity, prefs) }
                    .setNegativeButton(R.string.duraspeed_not_yet, null)
                    .show()
                return
            }
        }
        val stoppedAt = lastStop(activity, prefs.duraSpeedWarnedAt.get()) ?: return
        prefs.duraSpeedWarnedAt.set(stoppedAt)
        if (isOn(activity) == false) return
        // Stopped by DuraSpeed after being said to be allowed: it is not.
        prefs.duraSpeedAllowed.set(false)
        refreshNotification(activity, prefs)
        // Today's stop by its time alone; an older one with its day, or "8:02" reads as this morning.
        val flags = if (DateUtils.isToday(stoppedAt)) DateUtils.FORMAT_SHOW_TIME
            else DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY
        val when_ = DateUtils.formatDateTime(activity, stoppedAt, flags)
        activity.einkDialog()
            .setTitle(R.string.duraspeed_stopped_title)
            .setMessage(activity.getString(R.string.duraspeed_stopped_body, when_))
            .setPositiveButton(R.string.duraspeed_open) { _, _ -> open(activity) }
            .setNegativeButton(R.string.duraspeed_later, null)
            .show()
    }

    /** From the notification: the same words, and both answers. */
    fun showFix(activity: Activity, prefs: Preferences) {
        if (!needsAllowing(activity, prefs)) return
        activity.einkDialog()
            .setTitle(R.string.duraspeed_notification_title)
            .setMessage(R.string.duraspeed_notification_text)
            .setPositiveButton(R.string.duraspeed_open) { _, _ -> open(activity) }
            .setNegativeButton(R.string.duraspeed_allowed) { _, _ -> markAllowed(activity, prefs) }
            .setNeutralButton(R.string.duraspeed_later, null)
            .show()
    }

    /** DuraSpeed's App info page, whose Open button reaches the list. */
    private fun appInfo() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(android.net.Uri.parse("package:com.mediatek.duraspeed"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun open(context: Context) {
        askOnReturn = true
        runCatching { context.startActivity(appInfo()) }.onFailure {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
