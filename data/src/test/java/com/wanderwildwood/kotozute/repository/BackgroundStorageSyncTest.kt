package com.wanderwildwood.kotozute.repository

import com.wanderwildwood.kotozute.repository.BackgroundStorageSync.Decision
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The limit on storage reads nobody asked for while the app is off screen.
 *
 * Mirrors upstream `StorageSyncJobTest` at 0d8785bd33 ("given I am backgrounded and recently
 * synced in the background ..."), on the pure rule rather than a job.
 */
class BackgroundStorageSyncTest {

    private val now = 1_700_000_000_000L
    private val interval = BackgroundStorageSync.INTERVAL_MS
    private val minute = TimeUnit.MINUTES.toMillis(1)

    private fun decide(onScreen: Boolean, last: Long) = BackgroundStorageSync.decide(onScreen, last, now)

    @Test
    fun `the interval is upstream's six hours`() {
        assertEquals(TimeUnit.HOURS.toMillis(6), interval)
    }

    @Test
    fun `off screen and read a minute ago puts the read off`() {
        // Upstream: "then I touch nothing" and "then I mark that I need a sync on foreground".
        assertEquals(Decision.DEFER, decide(onScreen = false, last = now - minute))
        assertEquals(Decision.DEFER, decide(onScreen = false, last = now - interval + 1))
    }

    @Test
    fun `off screen and the interval is up reads and stamps`() {
        // Upstream: "last synced in the background long ago ... then I sync" and "record the
        // background sync time".
        assertEquals(Decision.RUN_IN_BACKGROUND, decide(onScreen = false, last = now - interval - minute))
        assertEquals("the interval itself is up", Decision.RUN_IN_BACKGROUND, decide(onScreen = false, last = now - interval))
    }

    @Test
    fun `off screen and never read in the background reads`() {
        assertEquals(Decision.RUN_IN_BACKGROUND, decide(onScreen = false, last = 0L))
    }

    @Test
    fun `a stamp in the future is a clock that moved, and reads`() {
        // Upstream: "my last background sync time is in the future, when I run, then I sync".
        assertEquals(Decision.RUN_IN_BACKGROUND, decide(onScreen = false, last = now + TimeUnit.HOURS.toMillis(1)))
    }

    @Test
    fun `on screen always reads and leaves the background stamp alone`() {
        // The control for the deferral above: the same stamp, a minute old, reads on screen.
        assertEquals(Decision.RUN, decide(onScreen = true, last = now - minute))
        assertEquals(Decision.RUN, decide(onScreen = true, last = 0L))
    }
}
