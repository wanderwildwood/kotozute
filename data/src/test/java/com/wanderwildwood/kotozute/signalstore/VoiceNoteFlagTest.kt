package com.wanderwildwood.kotozute.signalstore

import com.wanderwildwood.kotozute.signal.VoiceNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Marking an attachment as something somebody said, in both directions.
 *
 * Two separate mechanisms meet here and neither fails loudly:
 *
 * - **Going out**, the marker rides inside the data URI ([VoiceNotes]). If it is lost, the
 *   recording still uploads and still arrives; it is simply presented by every other Signal
 *   client as a file to download rather than as a voice message. Nothing errors.
 * - **Coming in**, the flag is one bit of `AttachmentPointer.flags`, which also carries
 *   BORDERLESS (2) and GIF (8). If it is read as a whole number rather than masked, a voice
 *   note that happens to carry another flag reads as an ordinary attachment. Nothing errors
 *   there either.
 *
 * Both failures are silent and both look like "Signal does not do that", so they are pinned.
 */
class VoiceNoteFlagTest {

    // --- the outgoing marker ---------------------------------------------------------------

    private val recording = "data:audio/aac;base64,AAAA"

    @Test
    fun `a marked recording reads back as a voice note`() {
        assertTrue(VoiceNotes.isMarked(VoiceNotes.mark(recording)))
    }

    /**
     * The control. Without this the test above proves only that `isMarked` can say yes --
     * a function that always said yes would pass it.
     */
    @Test
    fun `an unmarked recording does not`() {
        assertFalse(VoiceNotes.isMarked(recording))
    }

    /**
     * ⚠ The parameter belongs before the encoding token, not after it. After `;base64` it
     * would be read as part of the encoding rather than as a media-type parameter, and a
     * strict reader would reject the whole URI.
     */
    @Test
    fun `the marker goes before the base64 token`() {
        assertEquals("data:audio/aac;voice-note;base64,AAAA", VoiceNotes.mark(recording))
    }

    /** The content type still reads correctly to anything that does not know the parameter. */
    @Test
    fun `marking does not disturb the content type`() {
        val marked = VoiceNotes.mark(recording)
        val header = marked.substring("data:".length, marked.indexOf(','))
        assertEquals("audio/aac", header.substringBefore(';'))
    }

    /** Applying it twice is the same as applying it once. */
    @Test
    fun `marking is idempotent`() {
        val once = VoiceNotes.mark(recording)
        assertEquals(once, VoiceNotes.mark(once))
    }

    @Test
    fun `a data uri with no base64 token still takes the marker`() {
        assertTrue(VoiceNotes.isMarked(VoiceNotes.mark("data:audio/aac,AAAA")))
    }

    @Test
    fun `something that is not a data uri is left alone`() {
        assertEquals("/tmp/recording.aac", VoiceNotes.mark("/tmp/recording.aac"))
        assertFalse(VoiceNotes.isMarked("/tmp/recording.aac"))
    }

    /**
     * ⚠ A parameter that merely *contains* the marker's name is not the marker. Without the
     * exact match this would be true for `audio/aac;not-a-voice-note`.
     */
    @Test
    fun `a parameter that only looks like the marker does not count`() {
        assertFalse(VoiceNotes.isMarked("data:audio/aac;not-a-voice-note;base64,AAAA"))
    }

    // --- the incoming flag -----------------------------------------------------------------

    @Test
    fun `the voice message flag on its own is read`() {
        assertTrue(ContentNormalizer.isVoiceNote(VOICE_MESSAGE))
    }

    /**
     * The one this exists for. `flags == 1` passes every other test in this file and fails
     * this one, which is the real case: a client is free to set more than one flag.
     */
    @Test
    fun `the flag is read even when another flag is set beside it`() {
        assertTrue(ContentNormalizer.isVoiceNote(VOICE_MESSAGE or BORDERLESS))
        assertTrue(ContentNormalizer.isVoiceNote(VOICE_MESSAGE or GIF))
        assertTrue(ContentNormalizer.isVoiceNote(VOICE_MESSAGE or BORDERLESS or GIF))
    }

    /** The control again: the other flags alone must not read as a voice note. */
    @Test
    fun `other flags alone are not a voice note`() {
        assertFalse(ContentNormalizer.isVoiceNote(BORDERLESS))
        assertFalse(ContentNormalizer.isVoiceNote(GIF))
        assertFalse(ContentNormalizer.isVoiceNote(BORDERLESS or GIF))
        assertFalse(ContentNormalizer.isVoiceNote(0))
    }

    /** An attachment with no flags at all is the ordinary case, and must not throw. */
    @Test
    fun `absent flags are not a voice note`() {
        assertFalse(ContentNormalizer.isVoiceNote(null))
    }

    private companion object {
        /** `AttachmentPointer.Flags`, from SignalService.proto. */
        const val VOICE_MESSAGE = 1
        const val BORDERLESS = 2
        const val GIF = 8
    }
}
