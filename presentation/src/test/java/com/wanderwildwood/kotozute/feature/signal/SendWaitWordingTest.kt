package com.wanderwildwood.kotozute.feature.signal

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * How long the server says to wait, in words somebody would use.
 *
 * A rate-limited send used to say only "rate limited", which is a wall. The server usually
 * says how long — upstream backs off by exactly that value — and reporting it is the
 * difference between a wall and a queue.
 *
 * Here rather than beside the sender since the words moved to the screen: the sender passes on
 * the milliseconds, and [SignalWording] rounds them and says them.
 */
class SendWaitWordingTest {

    private fun wait(millis: Long) = English.of(SignalWording.waitFor(millis))

    @Test
    fun `a short wait is counted in seconds`() {
        assertEquals("30 seconds", wait(TimeUnit.SECONDS.toMillis(30)))
        assertEquals("89 seconds", wait(TimeUnit.SECONDS.toMillis(89)))
    }

    @Test
    fun `a longer one turns into minutes`() {
        assertEquals("2 minutes", wait(TimeUnit.SECONDS.toMillis(90)))
        assertEquals("5 minutes", wait(TimeUnit.MINUTES.toMillis(5)))
    }

    @Test
    fun `and a very long one into hours`() {
        assertEquals("2 hours", wait(TimeUnit.MINUTES.toMillis(90)))
        assertEquals("24 hours", wait(TimeUnit.HOURS.toMillis(24)))
    }

    @Test
    fun `it always rounds up, never down`() {
        // ⚠ The point of the rounding. Telling somebody to wait two minutes when it is really
        // two minutes and fifty seconds earns a second failure — and the second one reads as
        // the app being wrong rather than the server being busy.
        assertEquals("3 minutes", wait(TimeUnit.SECONDS.toMillis(121)))
        assertEquals("3 minutes", wait(TimeUnit.SECONDS.toMillis(170)))
        assertEquals("2 hours", wait(TimeUnit.SECONDS.toMillis(5401)))
    }

    @Test
    fun `a wait of nothing does not read as a number of seconds`() {
        // Zero never reaches this — the caller checks — but it should not produce nonsense.
        assertEquals("0 seconds", wait(0))
    }
}
