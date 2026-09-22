package com.wanderwildwood.kotozute.feature.desktopsync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What Desktop Sync tells the browser an attachment is.
 *
 * ⚠ **A media element handed `application/octet-stream` refuses to play.** So the browser's
 * audio and video players are only as good as this function: get it wrong and the page shows
 * a control that does nothing when pressed, with no error anywhere. That is precisely what
 * happened when the audio player was added — the player was written on the assumption that
 * this function already recognised audio, and it recognised only images and MP4.
 *
 * The sniff is on magic bytes because the served id does not carry a type.
 */
class ServedTypeSniffTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** Padding, so a candidate is long enough for the length guards to let it through. */
    private fun padded(vararg v: Int) = bytes(*v) + ByteArray(32)

    @Test
    fun `jpeg png gif and mp4 are recognised as before`() {
        assertEquals("image/jpeg", sniffServedType(padded(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("image/png", sniffServedType(padded(0x89, 0x50, 0x4E, 0x47)))
        assertEquals("image/gif", sniffServedType(padded(0x47, 0x49, 0x46, 0x38)))
        assertEquals(
            "video/mp4",
            sniffServedType(padded(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70))
        )
    }

    /** What this app records for Signal: raw ADTS AAC. 0xFFF sync, layer bits 00. */
    @Test
    fun `adts aac is recognised, which is what a voice note is`() {
        assertEquals("audio/aac", sniffServedType(padded(0xFF, 0xF1, 0x50, 0x80)))
        // 0xF9 is the other common second byte: MPEG-2 rather than MPEG-4, layer still 00.
        assertEquals("audio/aac", sniffServedType(padded(0xFF, 0xF9, 0x50, 0x80)))
    }

    /**
     * ⛔ The row this test exists for.
     *
     * MP3 and ADTS AAC both open with an eleven-bit sync run, so both match `FF Fx`. What
     * separates them is the two-bit layer field, which ADTS requires to be 00. Masking only
     * the top nibble -- `and 0xF0` -- serves every MP3 as `audio/aac`.
     *
     * 0xFB is an MPEG-1 Layer III frame: layer bits are 01, not 00.
     */
    @Test
    fun `an mp3 frame is not served as aac, though a looser mask would say it is`() {
        val mp3 = padded(0xFF, 0xFB, 0x90, 0x00)
        // The premise: it *does* pass the looser test, which is why the looser test is wrong.
        assertEquals(0xF0, mp3[1].toInt() and 0xF0)
        assertEquals("application/octet-stream", sniffServedType(mp3))
    }

    /** ⚠ JPEG must keep winning: FF D8 masks to D0, never F0, so the two cannot collide. */
    @Test
    fun `a jpeg is never mistaken for aac`() {
        val jpeg = padded(0xFF, 0xD8, 0xFF, 0xE0)
        assertEquals("image/jpeg", sniffServedType(jpeg))
        assertEquals(0xD0, jpeg[1].toInt() and 0xF0)
    }

    /** Opus or Vorbis in Ogg, which some other Signal clients send. */
    @Test
    fun `ogg is recognised`() {
        assertEquals("audio/ogg", sniffServedType(padded(0x4F, 0x67, 0x67, 0x53)))
    }

    /** AMR, from a recording this app's own MMS composer made. */
    @Test
    fun `amr is recognised`() {
        val amr = "#!AMR\n".toByteArray(Charsets.US_ASCII) + ByteArray(32)
        assertEquals("audio/amr", sniffServedType(amr))
    }

    /**
     * The control. Something unrecognised must stay unrecognised rather than be guessed at:
     * a wrong type makes a browser refuse a file it would otherwise have offered to save.
     */
    @Test
    fun `anything else is offered as a download`() {
        assertEquals("application/octet-stream", sniffServedType(padded(0x50, 0x4B, 0x03, 0x04)))
        assertEquals("application/octet-stream", sniffServedType(ByteArray(64)))
    }

    /** ⚠ Short and empty inputs must not throw; the guards are why each row checks a size. */
    @Test
    fun `a truncated file does not throw`() {
        assertEquals("application/octet-stream", sniffServedType(ByteArray(0)))
        assertEquals("application/octet-stream", sniffServedType(bytes(0xFF)))
        assertEquals("application/octet-stream", sniffServedType(bytes(0xFF, 0xF1)))
        assertEquals("application/octet-stream", sniffServedType(bytes(0x23, 0x21)))
    }
}
