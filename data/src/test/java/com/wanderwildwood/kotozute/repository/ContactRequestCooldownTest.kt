package com.wanderwildwood.kotozute.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * How often this phone may ask the primary to send its contacts.
 *
 * The ask is not free and it is not free on this phone. A linked device's request makes the
 * primary run a full contacts sync immediately, bypassing the six-hour cooldown it applies to
 * its own -- `SyncMessageProcessor` answers it with `MultiDeviceContactUpdateJob(true)`, and
 * the `true` is `forceSync`. Asking once per process start meant building and uploading the
 * whole contact list from somebody's other phone every time Android restarted this one, which
 * on a phone built to sleep is several times a day.
 */
class ContactRequestCooldownTest {

    private val now = 1_700_000_000_000L
    private val interval = TimeUnit.HOURS.toMillis(6)

    private fun due(askedAt: Long, at: Long = now) =
        SignalRepositoryImpl.contactRequestDue(askedAt, at)

    @Test
    fun `a device that has never asked must ask`() {
        // The case that has to work: right after linking, when this is the only way to learn
        // who anybody is. A zero stamp is that case, not a recent one.
        assertTrue(due(0))
        assertTrue(due(-1))
    }

    @Test
    fun `the interval is exact`() {
        assertTrue("six hours is up", due(now - interval))
        assertFalse("a millisecond short is not", due(now - interval + 1))
    }

    @Test
    fun `a restart minutes later does not ask again`() {
        // The whole point: Android restarts this process often, and each restart used to cost
        // the other phone a full contact upload.
        assertFalse(due(now - TimeUnit.MINUTES.toMillis(1)))
        assertFalse(due(now - TimeUnit.HOURS.toMillis(5)))
    }

    @Test
    fun `a clock that moved asks rather than waits`() {
        // Cost of asking once more: one sync on the other phone. Cost of not asking: a device
        // that knows nobody until the clock catches up.
        assertTrue(due(now + TimeUnit.HOURS.toMillis(1)))
    }
}
