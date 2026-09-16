package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.core.models.ServiceId
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.whispersystems.signalservice.api.messages.EnvelopeContentValidator
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.Envelope

/**
 * When a message says it needs a newer build than this one.
 *
 * `DataMessage.requiredProtocolVersion` is the sender stating the minimum understanding a client
 * needs to render the message **correctly**. Below it, the parts this build recognises still
 * parse — which is the danger, not the consolation. The enum is
 * `INITIAL 0, MESSAGE_TIMERS 1, VIEW_ONCE 2, VIEW_ONCE_VIDEO 3, REACTIONS 4,
 * CDN_SELECTOR_ATTACHMENTS 5, MENTIONS 6, PAYMENTS 7, POLLS 8`, so an older client showing what
 * it recognises turns a **view-once photo into a permanent one while its sender believes it
 * disappeared**.
 *
 * So [SignalReceiver] refuses these rather than letting them through, and says so in the
 * conversation. Upstream does the same at `MessageDecryptor:205` and
 * `MessageContentProcessor:435`.
 *
 * These tests pin the library behaviour that decision rests on, not the decision itself.
 */
class UnsupportedProtocolVersionTest {

    private val self = ServiceId.ACI.parseOrThrow("0a5ebe7e-9de7-41a5-a25f-6ace4f8e11d1")
    private val timestamp = 1234L

    private fun validate(requiredProtocolVersion: Int?): EnvelopeContentValidator.Result =
        EnvelopeContentValidator.validate(
            Envelope(clientTimestamp = timestamp),
            Content(
                dataMessage = DataMessage(
                    timestamp = timestamp,
                    requiredProtocolVersion = requiredProtocolVersion
                )
            ),
            self,
            CiphertextMessage.WHISPER_TYPE
        )

    @Test
    fun `a message needing more than this build understands is marked unsupported`() {
        val result = validate(DataMessage.ProtocolVersion.CURRENT.value + 1)
        assertTrue("$result", result is EnvelopeContentValidator.Result.UnsupportedDataMessage)
    }

    /** The control: at the version we implement, it is an ordinary message. */
    @Test
    fun `a message at exactly this build's version is valid`() {
        val result = validate(DataMessage.ProtocolVersion.CURRENT.value)
        assertTrue("$result", result is EnvelopeContentValidator.Result.Valid)
    }

    @Test
    fun `a message that states no version at all is valid`() {
        assertTrue(validate(null) is EnvelopeContentValidator.Result.Valid)
    }

    /**
     * ⚠ Pinned because the number is the whole mechanism. If a future copy of the service layer
     * arrives with a higher `CURRENT` and this app has not caught up with what that version
     * means, the refusal above stops firing for the very messages it exists to stop — silently.
     * A failure here is the signal to go and read what the new version added.
     */
    @Test
    fun `the version this build claims to understand is the one it was written against`() {
        assertEquals(8, DataMessage.ProtocolVersion.CURRENT.value)
        assertEquals(2, DataMessage.ProtocolVersion.VIEW_ONCE.value)
        assertEquals(7, DataMessage.ProtocolVersion.PAYMENTS.value)
    }
}
