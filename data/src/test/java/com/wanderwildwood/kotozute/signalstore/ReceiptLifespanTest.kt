package com.wanderwildwood.kotozute.signalstore

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long to go on trying to tell a sender their message arrived.
 *
 * A delivery receipt is not a setting and reveals nothing about the reader, so it is sent for
 * everything that arrives. Upstream treats a failed one as worth a day of unlimited retries --
 * `SendDeliveryReceiptJob` is `setLifespan(TimeUnit.DAYS.toMillis(1))` with
 * `setMaxAttempts(Parameters.UNLIMITED)` -- and this is that lifespan.
 */
class ReceiptLifespanTest {

    private val day = TimeUnit.DAYS.toMillis(1)
    private val now = 1_700_000_000_000L

    @Test
    fun `a receipt just owed is worth sending`() {
        assertTrue(SignalReceiptStore.stillWorthSending(since = now, now = now))
    }

    @Test
    fun `a day is the boundary, and it is exclusive`() {
        assertTrue(SignalReceiptStore.stillWorthSending(since = now - day + 1, now = now))
        assertFalse(SignalReceiptStore.stillWorthSending(since = now - day, now = now))
    }

    @Test
    fun `nothing older than the lifespan keeps waking the radio`() {
        assertFalse(SignalReceiptStore.stillWorthSending(since = now - day * 3, now = now))
    }

    /** Zero is the absence of a request, not a very old one. */
    @Test
    fun `an unowed row is owed nothing`() {
        assertFalse(SignalReceiptStore.stillWorthSending(since = 0, now = now))
    }

    /**
     * Its own number, deliberately. The resend's lifespan is the same today because upstream
     * chose the same day for both jobs, and folding them together would hide the day one of
     * them changes.
     */
    @Test
    fun `the lifespan is the receipt job's own day`() {
        assertEquals(day, SignalReceiptStore.RECEIPT_LIFESPAN_MS)
    }
}
