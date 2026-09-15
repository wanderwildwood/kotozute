package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
    fun `when nothing is referenced, everything old is abandoned`() {
        // The case a phone lands in after "delete all Signal data": no message rows, so no
        // references, so every file really is an orphan. An earlier version refused to sweep
        // on an empty set and made that the one case that never got cleaned. Upstream does
        // not special-case it either -- `filesOnDisk - filesInDb` is just the difference.
        assertTrue(SignalAttachments.isAbandoned("anything", old, emptySet(), now))
        // The grace period still applies, so this is not a way to delete a live download.
        assertFalse(SignalAttachments.isAbandoned("anything", now, emptySet(), now))
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

/**
 * The sweep run end to end against a real directory.
 *
 * The rule being right is not the same as the loop around it being right, and on the phone
 * this can only be observed by its own count -- a sweep that deleted the wrong file would
 * report the same number as one that deleted the right file. Here both are visible.
 */
class AttachmentSweepTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val now = 1_700_000_000_000L
    private val old = now - SignalAttachments.ORPHAN_GRACE_MS - 1

    private fun file(name: String, modified: Long) =
        folder.newFile(name).apply { writeText("x"); setLastModified(modified) }

    @Test
    fun `it removes exactly what nothing refers to`() {
        val kept = file("kept", old)
        val orphan = file("orphan", old)
        val fresh = file("fresh", now)

        val removed = SignalAttachments.sweep(
            folder.root.listFiles()!!, setOf("kept"), now
        )

        assertEquals(1, removed)
        assertTrue("a referenced file must survive", kept.exists())
        assertTrue("a file inside the grace period must survive", fresh.exists())
        assertFalse("the orphan should be gone", orphan.exists())
    }

    @Test
    fun `an empty directory is not an error`() {
        assertEquals(0, SignalAttachments.sweep(folder.root.listFiles()!!, setOf("kept"), now))
    }

    @Test
    fun `it refers to nothing and removes everything old`() {
        // The state a phone is in after every message has been deleted.
        val a = file("a", old)
        val b = file("b", old)
        val fresh = file("c", now)

        assertEquals(2, SignalAttachments.sweep(folder.root.listFiles()!!, emptySet(), now))
        assertFalse(a.exists())
        assertFalse(b.exists())
        assertTrue(fresh.exists())
    }
}
