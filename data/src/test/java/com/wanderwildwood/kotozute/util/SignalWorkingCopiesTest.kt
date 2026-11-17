package com.wanderwildwood.kotozute.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Which files in the cache are removed as unencrypted working copies of attachments.
 *
 * The cache is shared with everything else the app keeps there, so the test that matters is
 * the list of things that must survive.
 */
class SignalWorkingCopiesTest {

    @get:Rule val folder = TemporaryFolder()

    private val now = 1_800_000_000_000L
    private val old = now - SignalWorkingCopies.SHARED_KEPT_MS - 1

    private fun file(parent: File, name: String, modified: Long = old): File =
        File(parent, name).apply { parentFile?.mkdirs(); writeText("x"); setLastModified(modified) }

    @Test
    fun `copies a stopped playback left are removed whatever their age`() {
        val cache = folder.root
        val voice = file(cache, "voice-3f9a0c1d2e4b5a6978c0d1e2f3a4b5c6.aac", modified = now)
        val imported = file(cache, "voice-import-note_1.m4a.aac", modified = now)
        val video = file(cache, "view-once-video", modified = now)

        assertEquals(3, SignalWorkingCopies.clean(cache, now))

        assertFalse(voice.exists())
        assertFalse(imported.exists())
        assertFalse(video.exists())
    }

    @Test
    fun `a staged copy is left for two hours and then removed`() {
        val cache = folder.root
        val shared = SignalWorkingCopies.sharedDir(cache)
        val fresh = file(shared, "harbor.jpg", modified = now - 60_000)
        val almost = file(shared, "ticket.pdf", modified = now - SignalWorkingCopies.SHARED_KEPT_MS + 1000)
        val stale = file(shared, "map.png", modified = old)

        assertEquals(1, SignalWorkingCopies.clean(cache, now))

        assertTrue("another app may still be reading it", fresh.exists())
        assertTrue(almost.exists())
        assertFalse(stale.exists())
    }

    @Test
    fun `a staged copy stamped in the future is left alone`() {
        val shared = SignalWorkingCopies.sharedDir(folder.root)
        val odd = file(shared, "odd.jpg", modified = now + 3_600_000)
        assertEquals(0, SignalWorkingCopies.clean(folder.root, now))
        assertTrue(odd.exists())
        assertFalse(SignalWorkingCopies.isStale(0L, now))
        assertFalse(SignalWorkingCopies.isStale(now + 1, now))
        assertTrue(SignalWorkingCopies.isStale(old, now))
    }

    @Test
    fun `nothing else in the cache is touched`() {
        val cache = folder.root
        val survivors = listOf(
            // The recorder's own files, the SMS side's staging, and names that only resemble ours.
            file(cache, "recorded-1790000000000.aac"),
            file(cache, "voice-note.m4a"),
            file(cache, "voice-.aac"),
            file(cache, "my-voice-1.aac"),
            file(cache, "view-once-video.mp4"),
            file(cache, "pasted-1790000000000.jpg"),
            file(cache, "harbor.jpg"),
            file(File(cache, "cards"), "Ada Whitlock.vcf"),
            file(File(cache, "desktopsync-video"), "12-v3.mp4"),
            file(File(cache, "signal-attachment-downloads"), "att123.tmp"),
            // A folder that happens to carry one of the names is not a file to delete.
            file(File(cache, "view-once-video-dir"), "x"),
            file(File(File(cache, SignalWorkingCopies.SHARED_DIR), "nested"), "kept.jpg")
        )

        assertEquals(0, SignalWorkingCopies.clean(cache, now))

        survivors.forEach { assertTrue("${it.name} must survive", it.exists()) }
    }

    @Test
    fun `an empty or missing cache is nothing to do`() {
        assertEquals(0, SignalWorkingCopies.clean(File(folder.root, "absent"), now))
        assertEquals(0, SignalWorkingCopies.clean(folder.root, now))
    }
}
