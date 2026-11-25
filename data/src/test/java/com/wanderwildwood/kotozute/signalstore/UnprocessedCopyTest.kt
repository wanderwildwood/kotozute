package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.core.models.ServiceId
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.api.crypto.SignalServiceCipherResult
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.SyncMessage
import java.util.UUID

/**
 * A message that decrypted and then could not be processed.
 *
 * ⚠ The fault this guards: decryption and processing shared one `try`, so a throw while
 * processing -- after the ratchet had moved -- was handled as a message that would not decrypt.
 * The envelope was kept to be decrypted again, which can only come back as a duplicate, and a
 * duplicate is dropped as routine: the message was lost without a word, and for our own
 * account's transcripts the session was archived on the way. Upstream rolls the session back
 * with the processing (`IncomingMessageObserver.processBatchInTransaction`) and processes again.
 *
 * Here what decrypted is kept beside the envelope and processed again from the copy, never
 * decrypted twice. So the copy is the whole of what makes the message recoverable, and these
 * check that it comes back as exactly what the cipher produced.
 */
class UnprocessedCopyTest {

    private val self = ServiceId.ACI.from(UUID.fromString("0d0d0d0d-1e1e-4f2f-8a3a-4b4b4b4b4b4b"))
    private val theirs = ServiceId.ACI.from(UUID.fromString("11111111-2222-4333-8444-555555555555"))
    private val at = 1789646040389L

    /** Our own send from another device -- a transcript, the shape the report was about. */
    private fun transcript(group: ByteArray? = null, e164: String? = null) = SignalServiceCipherResult(
        Content(
            syncMessage = SyncMessage(
                sent = SyncMessage.Sent(
                    destinationServiceIdBinary = theirs.toByteString(),
                    timestamp = at,
                    message = DataMessage(body = "See you at six, Ada", timestamp = at)
                )
            )
        ),
        EnvelopeMetadata(
            sourceServiceId = self,
            sourceE164 = e164,
            sourceDeviceId = 3,
            sealedSender = false,
            groupId = group,
            destinationServiceId = self,
            ciphertextMessageType = CiphertextMessage.WHISPER_TYPE
        )
    )

    private fun assertSame(expected: SignalServiceCipherResult, actual: SignalServiceCipherResult?) {
        assertNotNull(actual)
        actual!!
        assertEquals(expected.content, actual.content)
        assertEquals(expected.metadata.sourceServiceId, actual.metadata.sourceServiceId)
        assertEquals(expected.metadata.sourceE164, actual.metadata.sourceE164)
        assertEquals(expected.metadata.sourceDeviceId, actual.metadata.sourceDeviceId)
        assertEquals(expected.metadata.sealedSender, actual.metadata.sealedSender)
        assertArrayEquals(expected.metadata.groupId, actual.metadata.groupId)
        assertEquals(expected.metadata.destinationServiceId, actual.metadata.destinationServiceId)
        assertEquals(expected.metadata.ciphertextMessageType, actual.metadata.ciphertextMessageType)
    }

    @Test
    fun `a transcript kept after a processing failure comes back whole`() {
        val decrypted = transcript()
        assertSame(decrypted, SignalReceiver.decodeUnprocessed(SignalReceiver.encodeUnprocessed(decrypted)))
    }

    @Test
    fun `the optional parts come back too`() {
        val decrypted = transcript(group = ByteArray(32) { it.toByte() }, e164 = "+15550100001")
        assertSame(decrypted, SignalReceiver.decodeUnprocessed(SignalReceiver.encodeUnprocessed(decrypted)))
    }

    /** What the receiver needs to rebuild the message from the copy, read off it again. */
    @Test
    fun `the message itself is what comes back`() {
        val back = SignalReceiver.decodeUnprocessed(SignalReceiver.encodeUnprocessed(transcript()))!!
        assertEquals("See you at six, Ada", back.content.syncMessage?.sent?.message?.body)
        assertEquals(theirs.toString(), ContentNormalizer.destinationServiceIdOf(back.content.syncMessage!!.sent!!))
    }

    /** Anything this build did not write is refused, not misread as a message. */
    @Test
    fun `bytes it did not write are refused`() {
        assertNull(SignalReceiver.decodeUnprocessed(ByteArray(0)))
        assertNull(SignalReceiver.decodeUnprocessed(byteArrayOf(0, 0, 0, 9, 1, 2, 3)))
        val good = SignalReceiver.encodeUnprocessed(transcript())
        assertNull(SignalReceiver.decodeUnprocessed(good.copyOf(good.size - 5)))
    }

    /**
     * Told apart from a message that would not decrypt, in the connection line and in a debug
     * log. The decryption failures are recorded as the exception's own name, so none of them
     * starts this way.
     */
    @Test
    fun `a processing failure says it was one`() {
        val line = SignalReceiver.processingFailure(IllegalStateException("contact row"))
        assertTrue(line.startsWith("processing: IllegalStateException"))
        assertTrue(line.contains("contact row"))
        // ⚠ Never mistaken for a duplicate, which the sweep deletes on sight.
        assertFalse(line.contains("DuplicateMessage"))
    }

    @Test
    fun `a very long reason is cut short`() {
        val line = SignalReceiver.processingFailure(RuntimeException("x".repeat(500)))
        assertTrue(line.length < 200)
    }
}
