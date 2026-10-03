package com.wanderwildwood.kotozute.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An iPhone reacting to a picture quotes nothing: "Loved an image". It has to read as a
 * reaction to that picture, not arrive as a message of its own.
 */
class AttachmentTapbackTest {

    @Test
    fun theSixTapbacksOnAPicture() {
        listOf(
            "Loved" to "❤️", "Liked" to "👍", "Disliked" to "👎",
            "Laughed at" to "😂", "Emphasized" to "‼️", "Questioned" to "❓",
        ).forEach { (phrase, emoji) ->
            assertEquals(
                ParsedEmojiReaction(emoji, "", attachmentType = "image/"),
                parseAttachmentTapback("$phrase an image"),
            )
        }
    }

    @Test
    fun removalsVideosAndOtherEmoji() {
        assertEquals(
            ParsedEmojiReaction("❤️", "", isRemoval = true, attachmentType = "image/"),
            parseAttachmentTapback("Removed a heart from an image"),
        )
        assertEquals(ParsedEmojiReaction("👍", "", attachmentType = "video/"), parseAttachmentTapback("Liked a movie"))
        assertEquals(ParsedEmojiReaction("🎉", "", attachmentType = ""), parseAttachmentTapback("Reacted 🎉 to an attachment"))
        assertEquals(
            ParsedEmojiReaction("🎉", "", isRemoval = true, attachmentType = "image/"),
            parseAttachmentTapback("Removed 🎉 from an image"),
        )
    }

    @Test
    fun ordinaryTextIsLeftAlone() {
        assertNull(parseAttachmentTapback("Loved “an image”"))
        assertNull(parseAttachmentTapback("I loved an image"))
        assertNull(parseAttachmentTapback("Loved an image of the river"))
        assertNull(parseAttachmentTapback("Removed an image"))
    }

    @Test
    fun whatWeSendReadsBackAsAReactionToThePicture() {
        EmojiReactionRepository.SMS_CHOICES.forEach { emoji ->
            for (remove in listOf(false, true)) {
                for ((type, prefix) in listOf("image/jpeg" to "image/", "video/mp4" to "video/", "audio/amr" to "")) {
                    val text = composeAttachmentTapback(emoji, type, remove)
                    assertEquals(text, ParsedEmojiReaction(emoji, "", remove, prefix), parseAttachmentTapback(text))
                }
            }
        }
        assertEquals("Loved an image", composeAttachmentTapback("❤️", "image/png", false))
        assertEquals("Removed a like from a movie", composeAttachmentTapback("👍", "video/3gpp", true))
    }

    @Test
    fun aShortenedQuoteMatchesTheWholeMessage() {
        val whole = "meet at the trailhead at six, bring the map and the good headlamp"
        assertTrue(truncatedQuoteRegex("meet at the trailhead at six, bring…").matches(whole))
        assertFalse(truncatedQuoteRegex("meet at the river…").matches(whole))
        assertTrue(truncatedQuoteRegex("see you at 6").matches("see you at 6"))
        assertFalse(truncatedQuoteRegex("see you at 6").matches("see you at 6 tomorrow"))
        // Regex characters in somebody's message are text, not pattern.
        assertTrue(truncatedQuoteRegex("cost? (\$5)").matches("cost? (\$5)"))
    }
}
