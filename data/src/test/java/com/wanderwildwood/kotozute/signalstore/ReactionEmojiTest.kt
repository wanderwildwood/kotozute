package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.signal.core.models.ServiceId
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.SyncMessage
import java.util.UUID

/**
 * A reaction is one emoji, so a reaction carrying anything else is dropped.
 *
 * Upstream DataMessageProcessor and SyncMessageProcessor do the same: the field is whatever
 * the sender's client wrote.
 */
class ReactionEmojiTest {

    @Test
    fun `an incoming reaction with one emoji is kept`() {
        val row = normalize(Content(dataMessage = reaction("👍")))
        assertNotNull(row)
        assertEquals("👍", row?.reactionEmoji)
    }

    @Test
    fun `an incoming reaction whose emoji is text is dropped`() {
        assertNull(normalize(Content(dataMessage = reaction("Your account is locked, reply now"))))
        assertNull(normalize(Content(dataMessage = reaction("👍👍"))))
        assertNull(normalize(Content(dataMessage = reaction(null))))
        assertNull(normalize(Content(dataMessage = reaction(""))))
    }

    @Test
    fun `a reaction in a sync transcript is held to the same rule`() {
        assertEquals("❤️", normalize(syncOf(reaction("❤️")))?.reactionEmoji)
        assertNull(normalize(syncOf(reaction("hello there"))))
    }

    private fun reaction(emoji: String?) = DataMessage(
        timestamp = 2_000L,
        reaction = DataMessage.Reaction(
            emoji = emoji,
            remove = false,
            targetAuthorAci = self.toString(),
            targetSentTimestamp = 1_000L
        )
    )

    private fun syncOf(message: DataMessage) = Content(
        syncMessage = SyncMessage(
            sent = SyncMessage.Sent(
                destinationServiceId = other.toString(),
                timestamp = message.timestamp,
                message = message
            )
        )
    )

    private fun normalize(content: Content) = ContentNormalizer.normalize(
        content,
        EnvelopeMetadata(
            sourceServiceId = ServiceId.ACI.from(other),
            sourceE164 = null,
            sourceDeviceId = 1,
            sealedSender = false,
            groupId = null,
            destinationServiceId = ServiceId.ACI.from(self),
            ciphertextMessageType = 0
        ),
        self.toString(),
        null,
        stickerPlaceholder = "(a sticker)"
    )

    private companion object {
        val self: UUID = UUID.fromString("0b0b0b0b-1111-4222-8333-444444444444")
        val other: UUID = UUID.fromString("0a0a0a0a-1111-4222-8333-444444444444")
    }
}
