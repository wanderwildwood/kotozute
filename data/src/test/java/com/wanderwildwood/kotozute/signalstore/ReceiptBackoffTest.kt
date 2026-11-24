package com.wanderwildwood.kotozute.signalstore

import com.wanderwildwood.kotozute.repository.SendFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The wait owed receipts keep after the server refused one with a challenge or a rate limit.
 *
 * Upstream f7174c6658: the receipt jobs answer `ChallengeRequired` and `RateLimited` with a retry
 * after `maxOf(defaultBackoff(), retryAfter)` (`SendErrorJobResults.kt`), never a failure.
 */
class ReceiptBackoffTest {

    private val now = 1_700_000_000_000L
    private val floor = ReceiptBackoff.DEFAULT_MAX_BACKOFF_MS
    private val day = TimeUnit.DAYS.toMillis(1)

    @Test
    fun `the floor is upstream's sixty-second default max backoff`() {
        assertEquals(60_000L, floor)
    }

    @Test
    fun `a challenge waits as long as the server says, and at least the floor`() {
        assertEquals(TimeUnit.HOURS.toMillis(2), ReceiptBackoff.waitFor(SendFailure.ProofRequired(2 * 3600)))
        assertEquals(floor, ReceiptBackoff.waitFor(SendFailure.ProofRequired(5)))
        // The returned form carries no time at all.
        assertEquals(floor, ReceiptBackoff.waitFor(SendFailure.ProofNeeded))
    }

    @Test
    fun `a rate limit waits as long as the server says, in either form`() {
        assertEquals(TimeUnit.MINUTES.toMillis(10), ReceiptBackoff.waitFor(SendFailure.RateLimited(600)))
        assertEquals(TimeUnit.MINUTES.toMillis(3), ReceiptBackoff.waitFor(SendFailure.TooFast(TimeUnit.MINUTES.toMillis(3))))
        assertEquals(floor, ReceiptBackoff.waitFor(SendFailure.TooFast(null)))
    }

    @Test
    fun `no wait is held past a receipt's own day`() {
        assertEquals(day, ReceiptBackoff.waitFor(SendFailure.ProofRequired(TimeUnit.DAYS.toSeconds(3))))
    }

    /**
     * The control: every other failure keeps the retry it had. A dropped connection must not
     * hold receipts back -- that is the case the owed store exists for.
     */
    @Test
    fun `control - other failures start no wait`() {
        assertNull(ReceiptBackoff.waitFor(SendFailure.Unreachable))
        assertNull(ReceiptBackoff.waitFor(SendFailure.ServerSilent))
        assertNull(ReceiptBackoff.waitFor(SendFailure.SafetyNumberChanged(null)))
        assertNull(ReceiptBackoff.waitFor(SendFailure.NotOnSignal(null)))
    }

    @Test
    fun `a wait holds until it ends, and not after`() {
        val until = now + floor
        assertTrue(ReceiptBackoff.holds(now, until))
        assertTrue(ReceiptBackoff.holds(until - 1, until))
        assertFalse(ReceiptBackoff.holds(until, until))
        assertFalse(ReceiptBackoff.holds(now, 0L))
    }

    @Test
    fun `a wait further off than a day is a clock that moved back, and does not hold`() {
        assertTrue(ReceiptBackoff.holds(now, now + day))
        assertFalse(ReceiptBackoff.holds(now, now + day + 1))
    }
}
