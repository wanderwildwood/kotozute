package com.wanderwildwood.kotozute.feature.signal

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
