package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.whispersystems.signalservice.internal.push.BodyRange
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.signal.core.models.ServiceId
import okio.ByteString.Companion.toByteString
import java.util.UUID

/**
 * What a message that mentions somebody actually says.
 *
 * ⚠ A mention is not in the text. Signal puts one U+FFFC per mention in the body and names the
 * person alongside it, so a client that reads only the body renders a stray box where a name
 * belongs -- in every group message that mentions anybody, and looking for all the world like
 * something the sender typed.
 */
class MentionBodyTest {

    private val alice = UUID.fromString("11111111-2222-4333-8444-555555555555")
    private val self = UUID.fromString("99999999-2222-4333-8444-555555555555")

    private fun bodyOf(
        body: String,
        ranges: List<BodyRange>,
        nameFor: (String) -> String? = { null }
    ): String? {
        val sender = UUID.fromString("77777777-2222-4333-8444-555555555555")
        val content = Content(
            dataMessage = DataMessage(
                body = body,
                timestamp = 1_000L,
                bodyRanges = ranges
            )
        )
        val metadata = EnvelopeMetadata(
            sourceServiceId = ServiceId.ACI.from(sender),
            sourceE164 = null,
            sourceDeviceId = 1,
            sealedSender = false,
            groupId = null,
            destinationServiceId = ServiceId.ACI.from(self),
            ciphertextMessageType = 0
        )
        return ContentNormalizer.normalize(
            content, metadata, self.toString(), null, nameFor
        )?.body
    }

    private fun mention(at: Int, who: UUID) = BodyRange(
        start = at,
        length = 1,
        mentionAciBinary = ByteArray(16).also { bytes ->
            val b = java.nio.ByteBuffer.wrap(bytes)
            b.putLong(who.mostSignificantBits)
            b.putLong(who.leastSignificantBits)
        }.toByteString()
    )

    @Test
    fun `a mention becomes the name we know them by`() {
        assertEquals(
            "@Alice did you see this",
            bodyOf("￼ did you see this", listOf(mention(0, alice))) { "Alice" }
        )
    }

    @Test
    fun `a mention of somebody unnamed is still not a stray character`() {
        // Eight characters of their id, the same fallback every list uses. Unhelpful, but it
        // reads as a person rather than as a corrupted message.
        assertEquals(
            "@11111111 did you see this",
            bodyOf("￼ did you see this", listOf(mention(0, alice)))
        )
    }

    @Test
    fun `two mentions in one message both land`() {
        // Applied back to front, because replacing the first one moves the second one's index.
        val body = "￼ and ￼"
        assertEquals(
            "@Alice and @Alice",
            bodyOf(body, listOf(mention(0, alice), mention(6, alice))) { "Alice" }
        )
    }

    @Test
    fun `a range that does not fit the body is ignored, not applied`() {
        // A malformed or hostile range must not throw and must not truncate the message.
        assertEquals("hello", bodyOf("hello", listOf(mention(99, alice))) { "Alice" })
    }

    @Test
    fun `a message with no ranges is left exactly as it was`() {
        assertEquals("hello", bodyOf("hello", emptyList()) { "Alice" })
    }

    // --- messages this build cannot draw -------------------------------------------------

    private fun normalized(message: DataMessage) = ContentNormalizer.normalize(
        Content(dataMessage = message),
        EnvelopeMetadata(
            sourceServiceId = ServiceId.ACI.from(UUID.fromString("77777777-2222-4333-8444-555555555555")),
            sourceE164 = null,
            sourceDeviceId = 1,
            sealedSender = false,
            groupId = null,
            destinationServiceId = ServiceId.ACI.from(self),
            ciphertextMessageType = 0
        ),
        self.toString(),
        null
    )

    @Test
    fun `a poll says what it asked instead of nothing`() {
        val poll = DataMessage(
            timestamp = 1_000L,
            pollCreate = DataMessage.PollCreate(question = "Pizza or curry?")
        )
        assertEquals("(poll) Pizza or curry?", normalized(poll)?.body)
    }

    @Test
    fun `a contact card is described rather than left blank`() {
        val card = DataMessage(
            timestamp = 1_000L,
            contact = listOf(DataMessage.Contact())
        )
        assertEquals("(a contact card)", normalized(card)?.body)
    }

    @Test
    fun `a message needing a newer app says so`() {
        val future = DataMessage(timestamp = 1_000L, requiredProtocolVersion = 9_999)
        assertEquals("(a message this version of the app cannot show)", normalized(future)?.body)
    }

    @Test
    fun `a vote is not a message in the conversation`() {
        // It belongs to its poll. Stored as a row it is a blank line where nothing happened.
        val vote = DataMessage(timestamp = 1_000L, pollVote = DataMessage.PollVote())
        assertNull(normalized(vote))
    }

    @Test
    fun `an ordinary message is untouched by any of this`() {
        val plain = DataMessage(timestamp = 1_000L, body = "morning")
        assertEquals("morning", normalized(plain)?.body)
    }
}
