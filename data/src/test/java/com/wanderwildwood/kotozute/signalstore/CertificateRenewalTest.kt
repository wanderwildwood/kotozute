package com.wanderwildwood.kotozute.signalstore

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
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
 * fetched first. Measured by this phone's clock, not the server's.
 */
class CertificateRenewalTest {

    private val now = 1_700_000_000_000L
    private val day = TimeUnit.DAYS.toMillis(1)

    /** The ordinary case: a week-long certificate, refreshed daily rather than held to the end. */
    @Test
    fun `a long certificate is refreshed daily, not held until it expires`() {
        assertEquals(now + day, SealedSender.renewAt(expiry = now + day * 7, now = now))
    }

    /**
     * The case the buffer decides. With the old one-hour margin this returned a time after the
     * certificate had a day left, so a message could go out under one with minutes remaining.
     */
    @Test
    fun `a short certificate stops being used a day before it expires`() {
        val expiry = now + TimeUnit.HOURS.toMillis(30)
        assertEquals(expiry - day, SealedSender.renewAt(expiry, now))
        assertTrue(SealedSender.renewAt(expiry, now) < expiry - TimeUnit.HOURS.toMillis(23))
    }

    /** Already inside the buffer: refuse it outright rather than squeeze one more send out. */
    @Test
    fun `a certificate with less than a day left is already spent`() {
        assertTrue(SealedSender.renewAt(expiry = now + TimeUnit.HOURS.toMillis(2), now = now) < now)
    }

    @Test
    fun `an expired certificate is never usable`() {
        assertTrue(SealedSender.renewAt(expiry = now - day, now = now) < now)
    }
}
