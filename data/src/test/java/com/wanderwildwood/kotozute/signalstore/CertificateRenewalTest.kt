package com.wanderwildwood.kotozute.signalstore

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a sealed-sender certificate stops being used.
 *
 * A certificate that expires between this device building a message and the recipient's client
 * checking it produces a message that client discards -- silently, and only for sealed sends.
 *
 * Upstream rotates a day ahead (`SealedSenderConstraint.ROTATION_LEAD_TIME`) and, since
 * 5be9220226 (2026-09-24), holds a send only once the certificate has actually expired, by
 * server time; before that commit it held sends for the whole last day too
 * (CERTIFICATE_EXPIRATION_BUFFER). This app cannot hold a send that a person is waiting on, so it
 * keeps the stricter rule: a certificate within a day of expiry is not used, and a fresh one is
 * fetched first. Measured, as upstream measures it since that commit, against the server's clock
 * where the server has been heard (`SignalStore.misc.estimatedServerTime`), and this phone's
 * clock otherwise.
 */
class CertificateRenewalTest {

    private val now = 1_700_000_000_000L
    private val day = TimeUnit.DAYS.toMillis(1)
    private val hour = TimeUnit.HOURS.toMillis(1)

    /** The phone clock alone, as before the server is heard. */
    private fun usable(expiry: Long, fetchedAt: Long = now, at: Long = now, offset: Long? = null) =
        SealedSender.stillUsable(expiry, fetchedAt, at, offset)

    /** The ordinary case: a week-long certificate, refreshed daily rather than held to the end. */
    @Test
    fun `a long certificate is refreshed daily, not held until it expires`() {
        assertTrue(usable(expiry = now + day * 7, at = now + day - 1))
        assertFalse(usable(expiry = now + day * 7, at = now + day))
    }

    /**
     * The case the buffer decides. With the old one-hour margin a message could go out under a
     * certificate with minutes remaining.
     */
    @Test
    fun `a short certificate stops being used a day before it expires`() {
        val expiry = now + 30 * hour
        assertTrue(usable(expiry, at = expiry - day - 1))
        assertFalse(usable(expiry, at = expiry - day))
        assertFalse(usable(expiry, at = expiry - 23 * hour))
    }

    /** Already inside the buffer: refuse it outright rather than squeeze one more send out. */
    @Test
    fun `a certificate with less than a day left is already spent`() {
        assertFalse(usable(expiry = now + 2 * hour))
    }

    @Test
    fun `an expired certificate is never usable`() {
        assertFalse(usable(expiry = now - day))
        assertFalse(usable(expiry = now - day, offset = 0L))
    }

    // The server's clock. A certificate with three days left by the server's clock; the phone's
    // clock is off by two days, and the server has been heard, so the offset is known.

    private val serverNow = now
    private val expiry = serverNow + 3 * day

    /**
     * A phone clock two days FAST reads the certificate as having one day left -- inside the
     * margin -- and would throw a good certificate away on every send. By server time it has three.
     */
    @Test
    fun `a phone clock two days fast keeps a certificate the server still honours`() {
        val phone = serverNow + 2 * day
        val offset = phone - serverNow
        assertTrue(usable(expiry, fetchedAt = phone, at = phone, offset = offset))
    }

    /**
     * A phone clock two days SLOW reads an expired certificate as having days left, and would
     * seal messages the recipient's client discards. By server time it is spent.
     */
    @Test
    fun `a phone clock two days slow refuses a certificate the server has expired`() {
        val serverLater = expiry + hour // the certificate has expired by the server's clock
        val phone = serverLater - 2 * day
        val offset = phone - serverLater
        assertFalse(usable(expiry, fetchedAt = phone - hour, at = phone, offset = offset))
    }

    /**
     * The control: the same two cases judged by the phone clock alone come out the other way,
     * so the two tests above pass because of the offset and not in spite of it.
     */
    @Test
    fun `control - the phone clock alone gets both skewed cases wrong`() {
        val fast = serverNow + 2 * day
        assertFalse("fast clock, no offset: a good certificate thrown away", usable(expiry, fetchedAt = fast, at = fast, offset = null))
        val serverLater = expiry + hour
        val slow = serverLater - 2 * day
        assertTrue("slow clock, no offset: an expired certificate used", usable(expiry, fetchedAt = slow - hour, at = slow, offset = null))
    }

    /** A zero offset is a phone clock that agrees with the server: the same answers as no offset. */
    @Test
    fun `a clock that agrees with the server answers as the phone clock does`() {
        assertEquals(usable(expiry, offset = null), usable(expiry, offset = 0L))
        assertTrue(usable(expiry, offset = 0L))
    }

    /** The carry-forward: phone minus server, with the server's time moved on monotonically. */
    @Test
    fun `the offset carries the server's time forward by the monotonic clock`() {
        // Heard the server at monotonic 1000; an hour later by the monotonic clock the phone
        // reads two days ahead of where the server now is.
        val heard = now
        val elapsedThen = 1_000L
        val elapsedNow = elapsedThen + hour
        val phone = heard + hour + 2 * day
        assertEquals(2 * day, ServerClock.offsetFrom(phone, heard, elapsedThen, elapsedNow))
        // And a phone clock that keeps time with the server has no offset at all.
        assertEquals(0L, ServerClock.offsetFrom(heard + hour, heard, elapsedThen, elapsedNow))
    }
}
