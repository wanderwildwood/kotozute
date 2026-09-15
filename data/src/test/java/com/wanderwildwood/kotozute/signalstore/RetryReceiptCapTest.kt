package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * How many times one person can make this phone ask for a message again.
 *
 * There was no cap. Every message that would not open made the phone rotate its prekeys and
 * post a retry receipt, and the answer to a retry receipt is another message -- which, if the
 * session is genuinely broken rather than momentarily confused, fails the same way. Two devices
 * can sit in that loop indefinitely, and on a phone built to stay asleep it is battery and data
 * spent going nowhere.
 */
class RetryReceiptCapTest {

    private val now = 1_700_000_000_000L
    private val them = "11111111-1111-1111-1111-111111111111"
    private val someoneElse = "22222222-2222-2222-2222-222222222222"

    private fun counts() = mutableMapOf<String, Pair<Int, Long>>()

    @Test
    fun `the tenth failure is still asked about and the eleventh is not`() {
        val counts = counts()
        repeat(10) { i ->
            assertTrue(
                "failure ${i + 1} should still be asked about",
                SignalReceiver.keepAskingAfterFailure(them, now + i, counts)
            )
        }
        assertFalse(SignalReceiver.keepAskingAfterFailure(them, now + 10, counts))
        assertFalse("and it stays stopped", SignalReceiver.keepAskingAfterFailure(them, now + 11, counts))
    }

    @Test
    fun `going quiet for three hours starts again from one`() {
        val counts = counts()
        repeat(11) { SignalReceiver.keepAskingAfterFailure(them, now, counts) }
        assertFalse(SignalReceiver.keepAskingAfterFailure(them, now, counts))

        val later = now + TimeUnit.HOURS.toMillis(3) + 1
        assertTrue("a quiet spell forgives", SignalReceiver.keepAskingAfterFailure(them, later, counts))
        // And the count really restarted rather than merely letting one through.
        repeat(9) { assertTrue(SignalReceiver.keepAskingAfterFailure(them, later, counts)) }
        assertFalse(SignalReceiver.keepAskingAfterFailure(them, later, counts))
    }

    @Test
    fun `just under three hours does not forgive`() {
        val counts = counts()
        repeat(11) { SignalReceiver.keepAskingAfterFailure(them, now, counts) }
        val almost = now + TimeUnit.HOURS.toMillis(3)
        assertFalse(SignalReceiver.keepAskingAfterFailure(them, almost, counts))
    }

    @Test
    fun `one person being noisy does not stop anybody else being asked`() {
        // The whole point of counting per sender. A broken session with one person must not
        // cost a message from a second.
        val counts = counts()
        repeat(11) { SignalReceiver.keepAskingAfterFailure(them, now, counts) }
        assertFalse(SignalReceiver.keepAskingAfterFailure(them, now, counts))
        assertTrue(SignalReceiver.keepAskingAfterFailure(someoneElse, now, counts))
    }
}
