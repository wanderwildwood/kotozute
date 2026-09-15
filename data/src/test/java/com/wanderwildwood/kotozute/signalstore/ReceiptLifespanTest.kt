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

    /**
     * The two kinds are stored together and must stay distinguishable, because one message can
     * owe both at once -- the sender told it arrived, and this account's own devices told it
     * was read. They are what the `kind` column holds, so the strings are not decoration.
     */
    @Test
    fun `the kinds are named apart`() {
        assertEquals("delivery", SignalReceiptStore.Kind.DELIVERY.value)
        assertEquals("read-sync", SignalReceiptStore.Kind.READ_SYNC.value)
        assertEquals("read-receipt", SignalReceiptStore.Kind.READ_RECEIPT.value)
        assertEquals(
            SignalReceiptStore.Kind.values().size,
            SignalReceiptStore.Kind.values().map { it.value }.toSet().size
        )
    }

    /**
     * Only one of them is behind a setting, and it is the one sent to the person who wrote the
     * message. A delivery receipt reveals nothing about the reader and a read sync goes to this
     * account's own devices; neither is anybody's to switch off.
     */
    @Test
    fun `only the read receipt is the one a person can switch off`() {
        assertEquals(
            listOf(SignalReceiptStore.Kind.READ_RECEIPT),
            SignalReceiptStore.Kind.values().filter { it == SignalReceiptStore.Kind.READ_RECEIPT }
        )
        assertFalse(SignalReceiptStore.Kind.DELIVERY == SignalReceiptStore.Kind.READ_RECEIPT)
        assertFalse(SignalReceiptStore.Kind.READ_SYNC == SignalReceiptStore.Kind.READ_RECEIPT)
    }
}
