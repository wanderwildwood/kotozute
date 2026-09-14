package com.wanderwildwood.kotozute.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which published versions are worth a notification.
 *
 * The rule exists because of this app's release rate rather than for its own sake: sixty-six
 * patches inside v1.19 and six tags in a busy day. Announcing each one teaches the reader that
 * the notification is noise, and the cost lands on the release that actually needed saying.
 */
class UpdateAnnounceTest {

    private fun announce(published: String, running: String) =
        UpdateCheckWorker.worthAnnouncing(published, running)

    @Test
    fun `a patch is not announced`() {
        assertFalse(announce("1.19.67", "1.19.66"))
        assertFalse(announce("1.19.99", "1.19.66"))
    }

    @Test
    fun `a minor is announced`() {
        assertTrue(announce("1.20.0", "1.19.66"))
    }

    @Test
    fun `a major is announced`() {
        assertTrue(announce("2.0.0", "1.19.66"))
    }

    /** The three-digit patch that the old versionCode packing could not tell from a minor. */
    @Test
    fun `a three digit patch is still only a patch`() {
        assertFalse(announce("1.19.100", "1.19.66"))
    }

    @Test
    fun `the same version is not announced`() {
        assertFalse(announce("1.19.66", "1.19.66"))
    }

    /**
     * Nothing older is ever announced. The check never offers one, but the gate should not be
     * the only thing standing between a bad answer and a notification.
     */
    @Test
    fun `an older version is not announced`() {
        assertFalse(announce("1.18.0", "1.19.66"))
        assertFalse(announce("0.9.0", "1.19.66"))
    }

    /** A debug build's version has a suffix on the patch, which must not read as a minor bump. */
    @Test
    fun `a suffixed running version does not trigger an announcement`() {
        assertFalse(announce("1.19.66", "1.19.66-debug"))
    }

}
