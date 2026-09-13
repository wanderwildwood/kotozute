package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How much of an attachment this device is willing to fetch.
 *
 * Every download used to pass one flat ceiling, so a CDN body far longer than the attachment
 * claimed to be was written to internal storage in full — and three times over, because the
 * download is retried — before the digest check rejected it. Signal bounds each download to
 * the length the sender declared, so an over-long body is cut off rather than stored.
 */
class AttachmentDownloadLimitTest {

    private val ceiling = 125L * 1024 * 1024

    @Test
    fun `an ordinary attachment is bounded by its own declared size`() {
        // A one-megabyte photo should not be allowed to arrive as a hundred and twenty-five.
        val limit = SignalAttachments.downloadLimitFor(1024L * 1024)
        assertTrue("limit $limit should be well under the ceiling", limit < ceiling)
        // Padding and cipher overhead mean the ciphertext is a little larger than the
        // plaintext, never smaller — a limit below the declared size would truncate a good
        // attachment, which is the failure mode worth guarding against.
        assertTrue("limit $limit must cover the declared size", limit > 1024L * 1024)
    }

    @Test
    fun `the ceiling still caps a very large declared size`() {
        // minOf, so the per-attachment bound can never exceed the global one.
        assertEquals(ceiling, SignalAttachments.downloadLimitFor(ceiling * 4))
    }

    @Test
    fun `the limit rises with the declared size`() {
        val small = SignalAttachments.downloadLimitFor(100L * 1024)
        val large = SignalAttachments.downloadLimitFor(10L * 1024 * 1024)
        assertTrue("$small should be less than $large", small < large)
    }

    @Test
    fun `an attachment larger than the ceiling is refused before it is fetched`() {
        // A download that cannot succeed, not started.
        assertNotNull(SignalAttachments.refuseReason(ceiling + 1))
    }

    @Test
    fun `an attachment that declares no size is refused`() {
        // Upstream's "Attachment has no declared size!" — without one there is nothing to
        // bound the download by, which is the whole mechanism above.
        assertNotNull(SignalAttachments.refuseReason(0))
        assertNotNull(SignalAttachments.refuseReason(-1))
    }

    @Test
    fun `an ordinary attachment is not refused`() {
        // The control. A guard that refuses everything would stop attachments working, which
        // is worse than the fault it prevents.
        assertNull(SignalAttachments.refuseReason(1024L * 1024))
        assertNull(SignalAttachments.refuseReason(1))
        assertNull(SignalAttachments.refuseReason(ceiling))
    }
}
