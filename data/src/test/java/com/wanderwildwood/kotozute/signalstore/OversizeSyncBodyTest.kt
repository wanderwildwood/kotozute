package com.wanderwildwood.kotozute.signalstore

import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.whispersystems.signalservice.api.messages.EnvelopeContentValidator
import org.signal.core.models.ServiceId
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.Envelope
import org.whispersystems.signalservice.internal.push.SyncMessage
import java.nio.ByteBuffer
import java.util.UUID

/**
 * A body too long for any client to show, arriving as the account's **own** sent transcript.
 *
 * ⚠ This is the path a live probe could not settle. A 3000-byte message sent from the
 * account's primary -- signal-cli, which carries the older `_151` service library and so has
 * no such limit on the way out -- was accepted and stored by the phone rather than refused,
 * and from outside there is no way to tell whether the sender trimmed it or this app failed
 * to reject it. The two have opposite consequences, so the question is put to the validator
 * directly instead, with the body the sender claimed to send.
 *
 * The rule itself is Signal's (`EnvelopeContentValidator`, `[DataMessage] Body exceeds ...`).
 * What is being checked here is that it is reached **through a sync sent transcript**, which
 * is the shape a Note to Self takes on every other device on the account.
 */
class OversizeSyncBodyTest {

    private val self = UUID.fromString("26960253-cf31-4b8b-8f1c-bb1c14f62b32")
    private val timestamp = 1789646040389L

    private fun aciBytes(id: UUID) = ByteBuffer.allocate(16)
        .putLong(id.mostSignificantBits)
        .putLong(id.leastSignificantBits)
        .array()
        .toByteString()

    private fun transcriptOf(body: String) = Content(
        syncMessage = SyncMessage(
            sent = SyncMessage.Sent(
                timestamp = timestamp,
                destinationServiceId = self.toString(),
                message = DataMessage(body = body, timestamp = timestamp)
            )
        )
    )

    private fun validate(body: String) = EnvelopeContentValidator.validate(
        Envelope(
            sourceServiceId = self.toString(),
            sourceServiceIdBinary = aciBytes(self),
            clientTimestamp = timestamp
        ),
        transcriptOf(body),
        ServiceId.ACI.from(self),
        CiphertextMessage.WHISPER_TYPE
    )

    @Test
    fun `an oversize body in a sent transcript is refused`() {
        val result = validate("oversize probe ".repeat(200))   // 3000 bytes, as the probe sent
        assertTrue(
            "a 3000-byte body reached the store instead of being refused: $result",
            result is EnvelopeContentValidator.Result.Invalid &&
                result.reason.contains("Body exceeds")
        )
    }

    /**
     * The control. Without it a refusal proves nothing: a validator that rejected *every*
     * transcript would pass the test above and break every message on the account.
     */
    @Test
    fun `an ordinary body in the same transcript is accepted`() {
        val result = validate("Running late, about twenty minutes.")
        assertTrue(
            "an ordinary sent transcript was refused: $result",
            result is EnvelopeContentValidator.Result.Valid
        )
    }
}
