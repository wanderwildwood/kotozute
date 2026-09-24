package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GIF flag, in both of Signal's numberings -- see [BackupVoiceFlagTest] for why there are
 * two and why they must not share a reader. Wire GIF is bit **8**; backup GIF is **3**.
 */
class GifFlagTest {

    @Test
    fun `the wire flag is a bit, and is found beside others`() {
        assertTrue(ContentNormalizer.isGif(8))
        assertTrue(ContentNormalizer.isGif(8 or 2))
        assertFalse(ContentNormalizer.isGif(1))
        assertFalse(ContentNormalizer.isGif(0))
        assertFalse(ContentNormalizer.isGif(null))
    }

    @Test
    fun `the backup flag is an equality on 3, by name or number`() {
        assertTrue(SignalHistoryImporter.isGifFlag("GIF"))
        assertTrue(SignalHistoryImporter.isGifFlag(3))
        assertTrue(SignalHistoryImporter.isGifFlag("3"))
    }

    /** ⛔ The wire's value read against the backup table, which is the mistake to catch. */
    @Test
    fun `the wire value is not a backup GIF, and neither is a voice message`() {
        assertFalse(SignalHistoryImporter.isGifFlag(8))
        assertFalse(SignalHistoryImporter.isGifFlag("VOICE_MESSAGE"))
        assertFalse(SignalHistoryImporter.isGifFlag(1))
        assertFalse(SignalHistoryImporter.isGifFlag(null))
    }
}
