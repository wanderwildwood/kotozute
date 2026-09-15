package com.wanderwildwood.kotozute.feature.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When an attachment is refused for being too large.
 *
 * The limit was already here; where it was applied was the fault. It compared the length of a
 * `ByteArray` that held the whole file, so a video far over the limit was not refused -- it was
 * loaded, and on a phone with a small heap the app died before reaching the line that would
 * have refused it. The size is now asked for before anything is read, which puts a weight on
 * the answer "I do not know".
 */
class AttachmentSizeTest {

    private val limit = SignalAttachment.MAX_BYTES.toLong()

    @Test
    fun `an ordinary photo is sent`() {
        assertFalse(SignalAttachment.tooLargeToSend(2L * 1024 * 1024))
    }

    @Test
    fun `the boundary is exact`() {
        assertFalse("a file of exactly the limit is allowed", SignalAttachment.tooLargeToSend(limit))
        assertTrue("one byte over is not", SignalAttachment.tooLargeToSend(limit + 1))
    }

    @Test
    fun `a video far over the limit is refused`() {
        assertTrue(SignalAttachment.tooLargeToSend(400L * 1024 * 1024))
    }

    @Test
    fun `an unknown size is not a refusal`() {
        // A provider that will not say how large a file is -- common for a file:// Uri -- must
        // not cost somebody a send they could have made. The check after the read is what
        // catches a file that really is too big; this one only spares the memory when the
        // answer was available.
        assertFalse(SignalAttachment.tooLargeToSend(0L))
        assertFalse(SignalAttachment.tooLargeToSend(-1L))
    }
}

/**
 * How far a picture is divided down before it is decoded.
 *
 * A bitmap costs four bytes a pixel whatever the file weighs, so a photo from a modern phone
 * is fifty megabytes decoded and was being decoded that way to fill a thumbnail a few hundred
 * pixels wide. The rule is pure so the boundary can be checked without a screen.
 */
class SampleSizeTest {

    @Test
    fun `a picture already small enough is not divided`() {
        assertEquals(1, SignalAttachment.sampleSizeFor(400, 300, 480))
        assertEquals(1, SignalAttachment.sampleSizeFor(480, 480, 480))
    }

    @Test
    fun `a phone photo is divided until it fits`() {
        // 4032x3024 against a 480-pixel panel: 4032/8 is 504, still over; 4032/16 is 252.
        assertEquals(16, SignalAttachment.sampleSizeFor(4032, 3024, 480))
    }

    @Test
    fun `only the longer side decides`() {
        assertEquals(4, SignalAttachment.sampleSizeFor(480, 1920, 480))
    }

    @Test
    fun `powers of two only`() {
        // BitmapFactory rounds inSampleSize down to a power of two, so any other value is a
        // number that does not mean what it says.
        for (edge in listOf(100, 480, 1600)) {
            for (w in listOf(1, 99, 640, 1024, 4032, 8000)) {
                val n = SignalAttachment.sampleSizeFor(w, w, edge)
                assertEquals("sample $n for ${w}px at $edge is not a power of two", 0, n and (n - 1))
            }
        }
    }

    @Test
    fun `an unreadable picture is not divided at all`() {
        // outWidth is zero when the decoder could not make sense of the bytes. Dividing by a
        // guess would be inventing a size for something that has none.
        assertEquals(1, SignalAttachment.sampleSizeFor(0, 0, 480))
        assertEquals(1, SignalAttachment.sampleSizeFor(4032, 3024, 0))
    }
}
