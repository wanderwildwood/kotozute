package com.wanderwildwood.kotozute.signalstore

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long to go on answering somebody who asked for a message again.
 *
 * `ContentHint.RESENDABLE` tells their client to show nothing and wait, so a resend this app
 * abandons after one attempt is a message lost with a promise attached to it. Upstream answers
 * a retry receipt with `ResendMessageJob` -- `setLifespan(TimeUnit.DAYS.toMillis(1))` and
 * `setMaxAttempts(Parameters.UNLIMITED)` -- and this is that lifespan.
 *
 * Both ends of it matter and neither shows up on a healthy account: a retry that quietly stops
 * happening loses the message, and one that never stops wakes the radio for ever.
 */
class ResendLifespanTest {

    private val day = TimeUnit.DAYS.toMillis(1)
    private val now = 1_700_000_000_000L

    @Test
    fun `a request just made is worth answering`() {
        assertTrue(SignalMessageLog.stillWorthResending(since = now, now = now))
    }

    @Test
    fun `a request from an hour ago is still worth answering`() {
        assertTrue(SignalMessageLog.stillWorthResending(since = now - TimeUnit.HOURS.toMillis(1), now = now))
    }

    @Test
    fun `a day is the boundary, and it is exclusive`() {
        assertTrue(SignalMessageLog.stillWorthResending(since = now - day + 1, now = now))
        assertFalse(SignalMessageLog.stillWorthResending(since = now - day, now = now))
    }

    @Test
    fun `nothing older than the lifespan keeps waking the radio`() {
        assertFalse(SignalMessageLog.stillWorthResending(since = now - day * 14, now = now))
    }

    /** A row that was never marked is not owed; zero is the absence, not an ancient request. */
    @Test
    fun `an unmarked row is owed nothing`() {
        assertFalse(SignalMessageLog.stillWorthResending(since = 0, now = now))
    }

    /** It is the same lifespan upstream gives the job, not the fortnight the material is kept. */
    @Test
    fun `the lifespan is a day, not the retention of the log itself`() {
        org.junit.Assert.assertEquals(day, SignalMessageLog.RESEND_LIFESPAN_MS)
    }
}
