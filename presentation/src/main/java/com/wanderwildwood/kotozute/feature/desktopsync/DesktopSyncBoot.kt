/*
 * Whether Desktop Sync comes back after the app's process went away: yes after an update, or
 * after the system stopped the app, as long as the phone has not restarted since the switch
 * went on; never after a reboot.
 */
package com.wanderwildwood.kotozute.feature.desktopsync

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * Which boot of the phone a moment belongs to.
 *
 * [bootCount] is Android's own count of boots (Settings.Global.BOOT_COUNT, readable by any app,
 * no permission), or -1 where a phone does not keep it. [elapsed] is the time since this boot
 * (it starts again from zero at every boot) and [wall] the clock time, both in milliseconds.
 */
data class BootMark(val bootCount: Int, val elapsed: Long, val wall: Long) {

    /** When this boot began, by the wall clock. Moves only if the clock is set. */
    val bootedAt: Long get() = wall - elapsed

    /** Saved in the preferences as three numbers. */
    fun encode(): String = "$bootCount,$elapsed,$wall"

    companion object {
        /**
         * How far the start of the boot may seem to move, by the clock being corrected, and still
         * count as the same boot, when the boot count cannot be read. A reboot moves it by at
         * least as long as the phone had been up when the switch went on.
         */
        const val CLOCK_SLACK_MS = 60_000L

        fun decode(saved: String?): BootMark? {
            val parts = saved?.split(',') ?: return null
            if (parts.size != 3) return null
            val count = parts[0].toIntOrNull() ?: return null
            val elapsed = parts[1].toLongOrNull() ?: return null
            val wall = parts[2].toLongOrNull() ?: return null
            return BootMark(count, elapsed, wall)
        }

        fun now(context: Context): BootMark = BootMark(
            bootCount = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1),
            elapsed = SystemClock.elapsedRealtime(),
            wall = System.currentTimeMillis(),
        )
    }
}

object DesktopSyncBoot {

    /**
     * Whether [now] is in the same boot as [saved]. Anything unsure is a different boot, so the
     * doubt always leaves Desktop Sync off.
     */
    fun sameBoot(saved: BootMark?, now: BootMark): Boolean {
        if (saved == null) return false
        // The time since boot only goes forward within one boot.
        if (now.elapsed < saved.elapsed) return false
        if (saved.bootCount >= 0 && now.bootCount >= 0) return saved.bootCount == now.bootCount
        return kotlin.math.abs(now.bootedAt - saved.bootedAt) <= BootMark.CLOCK_SLACK_MS
    }

    enum class Decision {
        /** Leave things as they are: the switch is off, or the relay is already up. */
        NOTHING,

        /** The phone restarted since the switch went on: the switch goes off, nothing starts. */
        OFF,

        /** Same boot (an update, or the system stopped the app): the relay starts again. */
        START,
    }

    /**
     * What to do when the app's process starts, when the app is updated, or when it is opened.
     * [on] is what the switch says, [running] whether the relay is up in this process.
     */
    fun decide(on: Boolean, running: Boolean, saved: BootMark?, now: BootMark): Decision = when {
        !on || running -> Decision.NOTHING
        sameBoot(saved, now) -> Decision.START
        else -> Decision.OFF
    }
}
