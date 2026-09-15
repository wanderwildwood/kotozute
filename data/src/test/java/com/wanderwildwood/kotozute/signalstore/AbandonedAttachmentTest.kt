package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which attachment file on disk should be deleted.
 *
 * This is the one rule in the app where being wrong deletes something that has no other copy
 * anywhere -- this store is the only place a received picture exists. So both directions are
 * checked, and the "I am not sure" cases are checked hardest: every one of them must come back
 * false, because leaving a file costs disk and removing one costs somebody their photograph.
 */
class AbandonedAttachmentTest {

    private val now = 1_700_000_000_000L
    private val old = now - SignalAttachments.ORPHAN_GRACE_MS - 1
    private val known = setOf("kept-1", "kept-2")

    @Test
    fun `a file a message still names is kept`() {
        assertFalse(SignalAttachments.isAbandoned("kept-1", old, known, now))
        assertFalse(SignalAttachments.isAbandoned("kept-2", old, known, now))
    }

    @Test
    fun `a file nothing names is removed`() {
        assertTrue(SignalAttachments.isAbandoned("nobody-asked-for-this", old, known, now))
    }

    @Test
    fun `a file that has just arrived is left alone`() {
        // It is written before the row that names it exists, so a download landing during the
        // sweep would otherwise be deleted a moment after it arrived.
        assertFalse(SignalAttachments.isAbandoned("just-downloaded", now, known, now))
        assertFalse(
            SignalAttachments.isAbandoned(
                "half-an-hour-old",
                now - SignalAttachments.ORPHAN_GRACE_MS / 2,
                known,
                now
            )
        )
    }

    @Test
    fun `the grace boundary is exact`() {
        val exactly = now - SignalAttachments.ORPHAN_GRACE_MS
        assertTrue("a file exactly at the grace period is old enough",
            SignalAttachments.isAbandoned("x", exactly, known, now))
        assertFalse("one millisecond younger is not",
            SignalAttachments.isAbandoned("x", exactly + 1, known, now))
    }

    @Test
    fun `a file whose age cannot be trusted is left alone`() {
        // A stat that returned nothing, a clock that went backwards, a file stamped in the
        // future. None of these is evidence that nobody wants the file.
        assertFalse(SignalAttachments.isAbandoned("x", 0L, known, now))
        assertFalse(SignalAttachments.isAbandoned("x", -1L, known, now))
        assertFalse(SignalAttachments.isAbandoned("x", now + 60_000, known, now))
    }
}
