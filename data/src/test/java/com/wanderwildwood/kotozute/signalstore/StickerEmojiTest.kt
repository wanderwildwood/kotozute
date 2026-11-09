package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.core.models.ServiceId
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.DataMessage
import java.util.UUID

/**
 * A sticker's emoji is shown as the message, so it has to be an emoji.
 *
 * Upstream 0ab9936dec: the field is whatever the sender's client wrote, and text in it would
 * read in the conversation and the notification as if it were what they said.
 */
class StickerEmojiTest {

    @Test
    fun `one emoji is one emoji`() {
        listOf(
            "😍", // smiling face with heart-shaped eyes
            "🕷", // spider
            "🤷", // person shrugging
            "🤷🏿‍♂️", // man shrugging, dark skin tone
            "👨‍👩‍👧‍👦", // family
            "👨🏻‍👩🏻‍👧🏻‍👦🏻",
            "❤️", // red heart
            "🇺🇸", // a flag
            "#️⃣", // keycap
            "🏴󠁧󠁢󠁳󠁣󠁴󠁿" // a subdivision flag
        ).forEach { assertTrue(it, SingleEmoji.isSingleEmoji(it)) }
    }

    @Test
    fun `text, or more than one, is not`() {
        listOf(
            null,
            "",
            "cat",
            "ᑢᗩᖶ",
            "♍︎♋︎⧫︎",
            "ᑢ",
            "¯\\_(ツ)_/¯",
            "😍hi",
            "😍 ",
            "😍😍",
            "😍‍",
            "🇺", // half a flag
            "🏻", // a skin tone on its own
            "Call me at once",
            "️".repeat(64) + "́"
        ).forEach { assertFalse(it.toString(), SingleEmoji.isSingleEmoji(it)) }
    }

    @Test
    fun `a sticker shows its emoji when it is one`() {
        assertEquals("😍", bodyOf(sticker("😍")))
    }

    @Test
    fun `a sticker whose emoji is text shows the placeholder instead`() {
        assertEquals(PLACEHOLDER, bodyOf(sticker("Your account is locked, reply now")))
        assertEquals(PLACEHOLDER, bodyOf(sticker("😍😍")))
        assertEquals(PLACEHOLDER, bodyOf(sticker(null)))
    }

    private fun sticker(emoji: String?) = DataMessage(
        timestamp = 1_000L,
        sticker = DataMessage.Sticker(
            packId = okio.ByteString.of(*ByteArray(16)),
            packKey = okio.ByteString.of(*ByteArray(32)),
            stickerId = 1,
            emoji = emoji
        )
    )

    private fun bodyOf(message: DataMessage): String? = ContentNormalizer.normalize(
        Content(dataMessage = message),
        EnvelopeMetadata(
            sourceServiceId = ServiceId.ACI.from(UUID.fromString("0a0a0a0a-1111-4222-8333-444444444444")),
            sourceE164 = null,
            sourceDeviceId = 1,
            sealedSender = false,
            groupId = null,
            destinationServiceId = ServiceId.ACI.from(self),
            ciphertextMessageType = 0
        ),
        self.toString(),
        null,
        stickerPlaceholder = PLACEHOLDER
    )?.body

    private companion object {
        val self: UUID = UUID.fromString("0b0b0b0b-1111-4222-8333-444444444444")
        const val PLACEHOLDER = "(a sticker)"
    }
}
