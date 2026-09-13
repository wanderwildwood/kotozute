package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Noticing that this phone's clock disagrees with the server's.
 *
 * The socket only learns the server's time when the server volunteers it, so comparing at
 * that moment and never again misses the case that actually happens: somebody changes the
 * clock while the socket is already connected. Signal's `ClockSkewDetector` keeps the server's
 * time against a monotonic reading so it can be carried forward and re-compared for free, and
 * this is that arithmetic.
 *
 * ⚠ These tests exist because both handsets are in sync to the second, so the live check is
 * silent and silence is not evidence. Here it can be driven past the threshold on purpose.
 */
class ClockSkewTest {

    private val monitor = SignalSocketHealthMonitor.Companion

    @Test
    fun `a clock that agrees shows no skew`() {
        // Server time read at monotonic 1000, checked at the same instant.
        assertEquals(0L, monitor.skewFrom(now = 500_000, serverTime = 500_000, takenAt = 1000, elapsedNow = 1000))
    }

    @Test
    fun `time passing is not skew`() {
        // Sixty seconds later by both clocks: the estimate moves with it, so still nothing.
        assertEquals(
            0L,
            monitor.skewFrom(now = 560_000, serverTime = 500_000, takenAt = 1000, elapsedNow = 61_000)
        )
    }

    @Test
    fun `a clock moved forward while connected is caught`() {
        // Nothing has passed monotonically, but the wall clock jumped two days ahead.
        val jump = TimeUnit.DAYS.toMillis(2)
        val skew = monitor.skewFrom(
            now = 500_000 + jump,
            serverTime = 500_000,
            takenAt = 1000,
            elapsedNow = 1000
        )
        assertEquals(jump, skew)
        assertTrue(skew > monitor.ALLOWED_SKEW_MS)
    }

    @Test
    fun `a clock moved backward is caught the same way`() {
        val jump = TimeUnit.DAYS.toMillis(3)
        val skew = monitor.skewFrom(
            now = 500_000_000 - jump,
            serverTime = 500_000_000,
            takenAt = 1000,
            elapsedNow = 1000
        )
        assertEquals(jump, skew)
        assertTrue(skew > monitor.ALLOWED_SKEW_MS)
    }

    @Test
    fun `the bound is Signal's own default`() {
        // `client.maxAllowedClockSkewSeconds`, whose default is 24.hours. Not a round number
        // chosen here: a sealed sender certificate is valid for a day, so a clock out by more
        // than that fails them whichever way it leans.
        assertEquals(TimeUnit.HOURS.toMillis(24), monitor.ALLOWED_SKEW_MS)
    }

    @Test
    fun `an hour out is tolerated`() {
        // A phone on the wrong timezone offset is not a broken clock, and blocking on it would
        // be worse than the fault. Only a day or more is reported.
        val skew = monitor.skewFrom(
            now = 500_000 + TimeUnit.HOURS.toMillis(1),
            serverTime = 500_000,
            takenAt = 1000,
            elapsedNow = 1000
        )
        assertFalse(skew > monitor.ALLOWED_SKEW_MS)
    }
}
