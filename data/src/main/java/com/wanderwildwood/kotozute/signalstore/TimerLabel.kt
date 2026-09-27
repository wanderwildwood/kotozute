package com.wanderwildwood.kotozute.signalstore

import android.content.res.Resources
import com.wanderwildwood.kotozute.data.R

/**
 * A disappearing-messages timer in words: "1 week", "8 hours". Upstream's
 * `ExpirationUtil.getExpirationDisplayValue`, the largest whole unit that fits.
 */
object TimerLabel {
    fun of(resources: Resources, seconds: Int): String = when {
        seconds < 60 -> resources.getQuantityString(R.plurals.signal_timer_seconds, seconds, seconds)
        seconds < 3_600 -> (seconds / 60).let { resources.getQuantityString(R.plurals.signal_timer_minutes, it, it) }
        seconds < 86_400 -> (seconds / 3_600).let { resources.getQuantityString(R.plurals.signal_timer_hours, it, it) }
        seconds < 604_800 -> (seconds / 86_400).let { resources.getQuantityString(R.plurals.signal_timer_days, it, it) }
        else -> (seconds / 604_800).let { resources.getQuantityString(R.plurals.signal_timer_weeks, it, it) }
    }
}
