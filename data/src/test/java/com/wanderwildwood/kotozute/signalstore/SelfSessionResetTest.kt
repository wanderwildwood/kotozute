package com.wanderwildwood.kotozute.signalstore

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long to wait before asking this account's own device for a fresh session again.
 *
 * When a sync message from one of our own devices will not decrypt, the session is archived and
 * a null message asks for a new one. The hourly interval exists so that a batch of undecryptable
 * envelopes cannot become a null message each -- upstream's `automaticSessionResetInterval`.
 *
 * ⚠ But upstream does not have to choose between the two things this does, because the send is
 * its own job: `AutomaticSessionResetJob` sets the interval and enqueues `NullMessageSendJob`,
 * which is a day of unlimited attempts. Here the interval was stamped *before* the attempt and
 * never revisited, so a null message that never went bought the same hour of silence as one that
 * did -- an hour in which this account's own messages went on failing to decrypt, which is the
 * exact thing the repair exists to end.
 */
class SelfSessionResetTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `a null message that went earns the full hour of quiet`() {
        assertEquals(
            now + TimeUnit.HOURS.toMillis(1),
            SignalReceiver.nextSelfResetAttempt(sent = true, now = now)
        )
    }

    @Test
    fun `one that did not go earns only a short wait`() {
        assertEquals(
            now + TimeUnit.MINUTES.toMillis(1),
            SignalReceiver.nextSelfResetAttempt(sent = false, now = now)
        )
    }

    /**
     * The point of the whole change: a failure must not buy the same silence as a success. If
     * these ever match, a broken session goes unrepaired for an hour again.
     */
    @Test
    fun `a failure waits far less than a success`() {
        val after = SignalReceiver.nextSelfResetAttempt(sent = true, now = now)
        val afterFailure = SignalReceiver.nextSelfResetAttempt(sent = false, now = now)
        assertTrue(afterFailure < after)
    }

    /** Still a wait, though. A batch of bad envelopes must not become a null message each. */
    @Test
    fun `even a failure waits`() {
        assertTrue(SignalReceiver.nextSelfResetAttempt(sent = false, now = now) > now)
    }
}
