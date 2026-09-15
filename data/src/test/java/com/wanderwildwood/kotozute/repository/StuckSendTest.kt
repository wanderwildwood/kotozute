package com.wanderwildwood.kotozute.repository

import com.wanderwildwood.kotozute.repository.MessageRepositoryImpl.Companion.STUCK_SEND_AFTER_MS
import com.wanderwildwood.kotozute.repository.MessageRepositoryImpl.Companion.isStuckSend
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a message that says it is sending has to be admitted to have failed.
 *
 * A message is marked sending *before* the send is attempted, so a send that never happens
 * leaves the row at MESSAGE_TYPE_OUTBOX -- which the conversation draws as "Sending…" and which
 * isFailedMessage() does not count, so the retry the UI already offers is never offered. Nothing
 * else revisits the row. One such message sat for eleven hours on a real phone, with a reply
 * somebody was waiting on inside it.
 *
 * Both ends of the boundary are wrong in different ways and neither shows up on a healthy
 * phone: a sweep that never fires leaves the message dead, and one that fires too readily marks
 * a send failed while it is still in flight.
 */
class StuckSendTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `a send that has only just begun is left alone`() {
        assertFalse(isStuckSend(sentAt = now, now = now))
        assertFalse(isStuckSend(sentAt = now - TimeUnit.SECONDS.toMillis(2), now = now))
    }

    @Test
    fun `the boundary is five minutes, and it is inclusive`() {
        assertFalse(isStuckSend(sentAt = now - STUCK_SEND_AFTER_MS + 1, now = now))
        assertTrue(isStuckSend(sentAt = now - STUCK_SEND_AFTER_MS, now = now))
    }

    @Test
    fun `the one that prompted this would have been caught`() {
        // Sent 20:50, still saying "Sending…" when it was found at 07:17 the next morning.
        assertTrue(isStuckSend(sentAt = now - TimeUnit.HOURS.toMillis(11), now = now))
    }

    /**
     * A row this cannot reason about is left alone. Marking somebody's message failed on a
     * guess is worse than leaving it saying the wrong thing.
     */
    @Test
    fun `a date of zero or in the future is not swept`() {
        assertFalse(isStuckSend(sentAt = 0, now = now))
        assertFalse(isStuckSend(sentAt = now + TimeUnit.DAYS.toMillis(1), now = now))
    }

    @Test
    fun `the threshold is five minutes`() {
        assertEquals(TimeUnit.MINUTES.toMillis(5), STUCK_SEND_AFTER_MS)
    }
}
