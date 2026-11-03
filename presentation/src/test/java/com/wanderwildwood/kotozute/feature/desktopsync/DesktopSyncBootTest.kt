package com.wanderwildwood.kotozute.feature.desktopsync

import com.wanderwildwood.kotozute.feature.desktopsync.DesktopSyncBoot.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desktop Sync after the process went away: back on in the same boot (an update, or the system
 * stopping the app), off after a reboot, and off whenever that cannot be told.
 */
class DesktopSyncBootTest {

    private val bootedAt = 1_800_000_000_000L
    private fun at(count: Int, elapsed: Long, booted: Long = bootedAt) = BootMark(count, elapsed, booted + elapsed)

    private val switchedOn = at(943, elapsed = 3_600_000)

    @Test
    fun `same boot keeps it on and starts it again`() {
        val later = at(943, elapsed = 7_200_000)
        assertTrue(DesktopSyncBoot.sameBoot(switchedOn, later))
        assertEquals(Decision.START, DesktopSyncBoot.decide(on = true, running = false, saved = switchedOn, now = later))
    }

    @Test
    fun `an update a moment later restarts it`() {
        val afterUpdate = at(943, elapsed = 3_600_000 + 20_000)
        assertEquals(Decision.START, DesktopSyncBoot.decide(on = true, running = false, saved = switchedOn, now = afterUpdate))
    }

    @Test
    fun `a different boot turns it off`() {
        val afterReboot = at(944, elapsed = 90_000_000, booted = bootedAt + 10_000_000)
        assertFalse(DesktopSyncBoot.sameBoot(switchedOn, afterReboot))
        assertEquals(Decision.OFF, DesktopSyncBoot.decide(on = true, running = false, saved = switchedOn, now = afterReboot))
    }

    @Test
    fun `time since boot going backwards is a reboot even with the same count`() {
        val early = at(943, elapsed = 40_000, booted = bootedAt + 9_000_000)
        assertEquals(Decision.OFF, DesktopSyncBoot.decide(on = true, running = false, saved = switchedOn, now = early))
    }

    @Test
    fun `switch off or relay already up does nothing`() {
        val later = at(943, elapsed = 7_200_000)
        assertEquals(Decision.NOTHING, DesktopSyncBoot.decide(on = false, running = false, saved = switchedOn, now = later))
        assertEquals(Decision.NOTHING, DesktopSyncBoot.decide(on = true, running = true, saved = switchedOn, now = later))
        val afterReboot = at(944, elapsed = 90_000_000, booted = bootedAt + 10_000_000)
        assertEquals(Decision.NOTHING, DesktopSyncBoot.decide(on = false, running = false, saved = switchedOn, now = afterReboot))
    }

    @Test
    fun `no saved boot (switched on by an older version) turns it off`() {
        val later = at(943, elapsed = 7_200_000)
        assertEquals(Decision.OFF, DesktopSyncBoot.decide(on = true, running = false, saved = null, now = later))
        assertEquals(Decision.OFF, DesktopSyncBoot.decide(on = true, running = false, saved = BootMark.decode(""), now = later))
    }

    @Test
    fun `without a boot count the start of the boot decides`() {
        val saved = at(-1, elapsed = 3_600_000)
        // Same boot, the clock corrected by a few seconds.
        assertTrue(DesktopSyncBoot.sameBoot(saved, at(-1, elapsed = 7_200_000, booted = bootedAt + 4_000)))
        // A reboot: the new boot began after the old one had been up an hour.
        assertFalse(DesktopSyncBoot.sameBoot(saved, at(-1, elapsed = 7_200_000, booted = bootedAt + 3_700_000)))
        // Only one side has a count: fall back to the start of the boot.
        assertTrue(DesktopSyncBoot.sameBoot(saved, at(943, elapsed = 7_200_000)))
    }

    @Test
    fun `the mark survives the preferences`() {
        assertEquals(switchedOn, BootMark.decode(switchedOn.encode()))
        assertNull(BootMark.decode("garbage"))
        assertNull(BootMark.decode("1,2"))
        assertNull(BootMark.decode(null))
    }
}
