package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * When to stop waiting for a message and tell the reader it never came.
 *
 * A message that would not open was kept, its sender was asked once to send it again, and if
 * they never did the conversation simply had a gap in it -- the only sign anywhere being a
 * count on a settings screen, which names nobody and cannot be acted on. A reader cannot ask
 * about a message they were never told existed.
 *
 * The whole feature is a timer, and a timer is the one thing that cannot be checked by looking
 * at it: too short and the note appears while the resend is still in flight, contradicted a
 * minute later by the message itself.
 */
class GaveUpWaitingTest {

    private val now = 1_700_000_000_000L
    private val hour = TimeUnit.HOURS.toMillis(1)

    @Test
    fun `a message that just failed is still worth waiting for`() {
        assertFalse(SignalReceiver.readyToGiveUp(now, now))
        assertFalse(SignalReceiver.readyToGiveUp(now - TimeUnit.MINUTES.toMillis(59), now))
    }

    @Test
    fun `the hour is exact`() {
        assertTrue("an hour is up", SignalReceiver.readyToGiveUp(now - hour, now))
        assertFalse("a millisecond short is not", SignalReceiver.readyToGiveUp(now - hour + 1, now))
    }

    @Test
    fun `it matches upstream's lifespan`() {
        // PendingRetryReceiptManager.RETRY_RECEIPT_LIFESPAN is one hour. Long enough that a
        // phone which was merely asleep has woken; short enough that the gap is still part of
        // a conversation somebody remembers having.
        assertTrue(SignalReceiver.readyToGiveUp(now - TimeUnit.HOURS.toMillis(1), now))
    }

    @Test
    fun `a time that cannot be trusted is not ready`() {
        // A stored time in the future, or none at all. Waiting costs the reader nothing they
        // did not already have; announcing a loss that has not happened costs them a message
        // they go and ask about for no reason.
        assertFalse(SignalReceiver.readyToGiveUp(now + hour, now))
        assertFalse(SignalReceiver.readyToGiveUp(0, now))
        assertFalse(SignalReceiver.readyToGiveUp(-1, now))
    }
}
