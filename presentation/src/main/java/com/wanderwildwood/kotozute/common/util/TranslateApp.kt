package com.wanderwildwood.kotozute.common.util

import android.content.Context
import android.content.Intent

/**
 * Translate (tsuyaku), offered in a message's actions when it is on the phone.
 *
 * It opens Translate's own panel, the one it shows over selected text in any app, on the
 * whole message: the bubbles stay unselectable, and nothing comes back to replace the text.
 * Where Translate is not installed there is no such action at all.
 */
object TranslateApp {
    const val PACKAGE = "com.wanderwildwood.tsuyaku"

    /** The request for [text], or null when Translate is not here to take it. */
    fun intent(context: Context, text: String): Intent? {
        if (text.isBlank()) return null
        val intent = Intent(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
            .setPackage(PACKAGE)
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        return intent.takeIf { it.resolveActivity(context.packageManager) != null }
    }
}
