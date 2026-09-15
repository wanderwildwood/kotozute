package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * When a signed prekey is too old to go on sending with.
 *
 * `MAXIMUM_SIGNED_PREKEY_AGE_MS`'s own doc used to say the guard did not exist -- "the constant
 * is here so the number has one home when it gets one". The refresh runs every two days, so
 * this should never fire, and "should never fire" is exactly the case worth guarding: the way
 * it fires is that refresh having failed quietly for a fortnight, while every new session went
 * on being signed with a key that stopped being fresh long ago.
 */
class StaleSignedPreKeyTest {

    private val limit = PreKeyUploader.MAXIMUM_SIGNED_PREKEY_AGE_MS

    @Test
    fun `a key refreshed on schedule is fine`() {
        assertFalse(PreKeyUploader.tooOldToSendWith(0))
        assertFalse(PreKeyUploader.tooOldToSendWith(TimeUnit.DAYS.toMillis(2)))
    }

    @Test
    fun `the boundary is exact`() {
        assertFalse("a key exactly at the limit may still be used", PreKeyUploader.tooOldToSendWith(limit))
        assertTrue("one millisecond past it may not", PreKeyUploader.tooOldToSendWith(limit + 1))
    }

    @Test
    fun `a clock that went backwards counts as old`() {
        // Upstream refuses on a negative age too. A key whose age cannot be trusted is not one
        // to go on signing with for another fortnight.
        assertTrue(PreKeyUploader.tooOldToSendWith(-1))
    }

    @Test
    fun `an age that could not be read does not stop a send`() {
        // The direction that costs least. Refusing here costs somebody their message over a
        // question nobody answered; sending costs at most one more interval of a key the
        // maintenance pass is about to replace anyway.
        assertFalse(PreKeyUploader.tooOldToSendWith(null))
    }

    @Test
    fun `it is stricter than the refresh cadence, and deliberately so`() {
        // The two rules must not be confused: refreshOwed is a cadence, tooOldToSendWith is a
        // ceiling. An age past the cadence is due a refresh and perfectly fine to send with.
        val pastCadence = PreKeyUploader.REFRESH_INTERVAL_MS + 1
        assertTrue(PreKeyUploader.refreshOwed(pastCadence))
        assertFalse(PreKeyUploader.tooOldToSendWith(pastCadence))
    }
}
