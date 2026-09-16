package com.wanderwildwood.kotozute.signalstore

import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.core.models.ServiceId
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.whispersystems.signalservice.api.messages.EnvelopeContentValidator
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.Envelope
import org.whispersystems.signalservice.internal.push.GroupContextV2

/**
 * Three refusals `EnvelopeContentValidator` makes that upstream does not test.
 *
 * Moving the service layer to Signal's own source brought nine new refusals with it -- envelopes
 * the previous build accepted and this one drops. Six are covered by Signal's own
 * `EnvelopeContentValidatorTest`, which now runs here. These three are not covered by anything:
 * `validateGroupContextV2` (`EnvelopeContentValidator.kt:595`) answers *Missing GV2 master key*,
 * *Missing GV2 revision* and *Bad GV2 master key*, and no upstream test reaches it.
 *
 * ⚠ **OURS, and it tests upstream's code rather than this app's.** That is deliberate: a dropped
 * envelope is invisible by construction -- the sender sees a tick, the recipient sees nothing --
 * so the rules that drop one are worth pinning even when they belong to somebody else. It lives
 * here rather than in `signal-service/` because that tree is Signal's, byte for byte, and a test
 * added there is a fork nobody would remember making.
 *
 * The control is the last test: the same message with a well-formed master key is **valid**, so a
 * pass above means the rule fired rather than the fixture being wrong in some other way.
 */
class GroupContextRefusalTest {

    private val self = ServiceId.ACI.parseOrThrow("0a5ebe7e-9de7-41a5-a25f-6ace4f8e11d1")

    /** A group master key is 32 bytes; libsignal's `GroupMasterKey` rejects any other length. */
    private val validMasterKey = ByteArray(32) { (it + 1).toByte() }

    private fun validate(group: GroupContextV2): EnvelopeContentValidator.Result {
        val timestamp = 1234L
        return EnvelopeContentValidator.validate(
            Envelope(clientTimestamp = timestamp),
            Content(dataMessage = DataMessage(timestamp = timestamp, groupV2 = group)),
            self,
            CiphertextMessage.WHISPER_TYPE
        )
    }

    @Test
    fun `a group message with no master key is refused`() {
        val result = validate(GroupContextV2(revision = 0))
        assertTrue("$result", result is EnvelopeContentValidator.Result.Invalid)
    }

    @Test
    fun `a group message with no revision is refused`() {
        val result = validate(GroupContextV2(masterKey = validMasterKey.toByteString()))
        assertTrue("$result", result is EnvelopeContentValidator.Result.Invalid)
    }

    @Test
    fun `a group message whose master key is the wrong length is refused`() {
        val result = validate(
            GroupContextV2(masterKey = ByteArray(31).toByteString(), revision = 0)
        )
        assertTrue("$result", result is EnvelopeContentValidator.Result.Invalid)
    }

    /**
     * The control. Without it the three above pass for a well-formed message too, and would keep
     * passing if the group context stopped being validated at all.
     */
    @Test
    fun `a group message with a well-formed master key and a revision is accepted`() {
        val result = validate(
            GroupContextV2(masterKey = validMasterKey.toByteString(), revision = 0)
        )
        assertTrue("$result", result is EnvelopeContentValidator.Result.Valid)
    }
}
