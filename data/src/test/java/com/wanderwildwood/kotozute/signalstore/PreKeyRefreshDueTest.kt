package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * When the repeated-use keys are due to be replaced.
 *
 * The interval is Signal's `PreKeysSyncJob.REFRESH_INTERVAL` — two days — and not its
 * `MAXIMUM_ALLOWED_SIGNED_PREKEY_AGE`, which is fourteen and means something else entirely:
 * the age at which Signal refuses to send until the key has been rotated. This app used the
 * second number as its cadence, so it sat permanently at the age Signal treats as a fault.
 */
class PreKeyRefreshDueTest {

    private val interval = PreKeyUploader.REFRESH_INTERVAL_MS

    @Test
    fun `two days, not fourteen`() {
        assertTrue(interval == TimeUnit.DAYS.toMillis(2))
        // The ceiling still exists, under its own name, for the send guard it belongs to.
        assertTrue(PreKeyUploader.MAXIMUM_SIGNED_PREKEY_AGE_MS == TimeUnit.DAYS.toMillis(14))
    }

    @Test
    fun `a key that cannot be read is replaced`() {
        // A device that cannot load its own active signed prekey is exactly the one that
        // should replace it: the server is advertising a key whose private half is gone, and
        // every new session with this device fails until it is.
        assertTrue(PreKeyUploader.refreshOwed(null))
    }

    @Test
    fun `a fresh key is left alone`() {
        assertFalse(PreKeyUploader.refreshOwed(0))
        assertFalse(PreKeyUploader.refreshOwed(TimeUnit.HOURS.toMillis(1)))
        assertFalse(PreKeyUploader.refreshOwed(interval - 1))
    }

    @Test
    fun `at the interval and past it, it goes`() {
        assertTrue(PreKeyUploader.refreshOwed(interval))
        assertTrue(PreKeyUploader.refreshOwed(interval + 1))
        assertTrue(PreKeyUploader.refreshOwed(TimeUnit.DAYS.toMillis(30)))
    }

    @Test
    fun `a clock that moved backwards does not freeze rotation for ever`() {
        // Without this the key sits permanently in the future, every age is negative, and the
        // interval never comes round again — on machines that keep their RTC in local time,
        // which these do. Signal tests `< 0` beside the threshold in both the sync job and the
        // send path for the same reason.
        assertTrue(PreKeyUploader.refreshOwed(-1))
        assertTrue(PreKeyUploader.refreshOwed(-TimeUnit.DAYS.toMillis(365)))
    }
}
