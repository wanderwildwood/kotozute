package com.wanderwildwood.kotozute.common.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.activity.result.contract.ActivityResultContract
import com.wanderwildwood.kotozute.R
import java.util.Date
import java.util.Locale

/**
 * "Send where I am": one location, once, from Whereabouts (ibasho), which asks the person
 * before it hands anything over. Messaging holds no location permission and gets none; the
 * attach option is there only when a Whereabouts that offers this is installed.
 *
 * The action and extras are the ones ibasho's README documents.
 */
object Whereabouts {

    private const val PACKAGE = "com.wanderwildwood.ibasho"
    private const val ACTION = "com.wanderwildwood.ibasho.action.SHARE_LOCATION_ONCE"
    private const val EXTRA_LATITUDE = "latitude"
    private const val EXTRA_LONGITUDE = "longitude"
    private const val EXTRA_ACCURACY = "accuracy"
    private const val EXTRA_TIME = "time"
    private const val EXTRA_REASON = "reason"
    private const val REASON_NO_FIX = "no_fix"
    private const val REASON_NO_PERMISSION = "no_permission"

    /** A fix older than this says when it was taken. */
    private const val STALE_MS = 2 * 60_000L

    private fun intent() = Intent(ACTION).setPackage(PACKAGE)

    fun isAvailable(context: Context): Boolean =
        context.packageManager.queryIntentActivities(intent(), 0).isNotEmpty()

    sealed interface Result {
        data class Place(val latitude: Double, val longitude: Double, val accuracy: Float?, val time: Long) : Result
        /** The person said no, or backed out. Nothing to say about it. */
        data object Cancelled : Result
        data object NoFix : Result
        data object NoPermission : Result
    }

    class AskOnce : ActivityResultContract<Unit, Result>() {
        override fun createIntent(context: Context, input: Unit): Intent = intent()

        override fun parseResult(resultCode: Int, intent: Intent?): Result {
            if (resultCode == Activity.RESULT_OK && intent != null &&
                intent.hasExtra(EXTRA_LATITUDE) && intent.hasExtra(EXTRA_LONGITUDE)
            ) {
                return Result.Place(
                    latitude = intent.getDoubleExtra(EXTRA_LATITUDE, 0.0),
                    longitude = intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0),
                    accuracy = intent.takeIf { it.hasExtra(EXTRA_ACCURACY) }
                        ?.getFloatExtra(EXTRA_ACCURACY, 0f),
                    time = intent.getLongExtra(EXTRA_TIME, System.currentTimeMillis()),
                )
            }
            return when (intent?.getStringExtra(EXTRA_REASON)) {
                REASON_NO_FIX -> Result.NoFix
                REASON_NO_PERMISSION -> Result.NoPermission
                else -> Result.Cancelled
            }
        }
    }

    /** The words for a refusal that is worth telling the person about, or null. */
    fun problem(context: Context, result: Result): String? = when (result) {
        Result.NoFix -> context.getString(R.string.whereabouts_no_fix)
        Result.NoPermission -> context.getString(R.string.whereabouts_no_permission)
        else -> null
    }

    /**
     * The message: a plain map link and the coordinates, which any phone can read and any map
     * can take. A fix that is a few minutes old says when it was taken.
     */
    fun message(context: Context, place: Result.Place, now: Long = System.currentTimeMillis()): String {
        val lat = String.format(Locale.US, "%.5f", place.latitude)
        val lon = String.format(Locale.US, "%.5f", place.longitude)
        val link = "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=17/$lat/$lon"
        val lines = mutableListOf(context.getString(R.string.whereabouts_message, link), "$lat, $lon")
        place.accuracy?.takeIf { it > 0f }?.let {
            lines += context.getString(R.string.whereabouts_message_accuracy, Math.round(it))
        }
        if (now - place.time > STALE_MS) {
            lines += context.getString(
                R.string.whereabouts_message_as_of,
                DateFormat.getTimeFormat(context).format(Date(place.time)),
            )
        }
        return lines.joinToString("\n")
    }
}
