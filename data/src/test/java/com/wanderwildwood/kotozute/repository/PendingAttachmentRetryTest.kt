package com.wanderwildwood.kotozute.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * How long to keep asking for an attachment that did not arrive.
 *
 * Three immediate attempts cover a dropped socket. They do not cover a phone with no usable
 * connection for the length of one batch, which on a device built to sleep is an ordinary
 * evening -- and with the pointer thrown away there was nothing left to try again with, so the
 * message said "attachment, not downloaded" until the CDN copy expired weeks later.
 */
class PendingAttachmentRetryTest {

    private val now = 1_700_000_000_000L
    private val day = TimeUnit.DAYS.toMillis(1)

    private fun worth(firstTried: Long, at: Long = now) =
        SignalRepositoryImpl.stillWorthFetching(firstTried, at)

    @Test
    fun `an attachment that just failed is worth another go`() {
        assertTrue(worth(now))
        assertTrue(worth(now - TimeUnit.MINUTES.toMillis(5)))
        assertTrue(worth(now - TimeUnit.HOURS.toMillis(23)))
    }

    @Test
    fun `the day is exact`() {
        assertFalse("a full day is up", worth(now - day))
        assertTrue("a millisecond short is not", worth(now - day + 1))
    }

    @Test
    fun `it matches upstream's lifespan`() {
        // AttachmentDownloadJob is setLifespan(TimeUnit.DAYS.toMillis(1)) with
        // setMaxAttempts(UNLIMITED). Past it the CDN copy is still there for a while, but
        // somebody waiting on a picture has long since stopped waiting.
        assertFalse(worth(now - TimeUnit.DAYS.toMillis(1)))
        assertFalse(worth(now - TimeUnit.DAYS.toMillis(2)))
    }

    @Test
    fun `an entry written before this existed gets the benefit of the window`() {
        // A zero first-tried time is a row from an older build. Abandoning it on sight would
        // throw away the one chance those rows have.
        assertTrue(worth(0))
        assertTrue(worth(-1))
    }

    @Test
    fun `a clock that moved keeps trying`() {
        // The cost of one more attempt is a request; the cost of stopping is a picture.
        assertTrue(worth(now + TimeUnit.HOURS.toMillis(2)))
    }
}
