package com.wanderwildwood.kotozute.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * An SMS reaction this phone sends has to read back as one -- here, and on the phone it goes
 * to. The parser's own English patterns stand in for the other phone: they are the iPhone's
 * phrases, which is what Google Messages matches too.
 */
class ComposeTapbackTest {

    private val patterns: Map<String, Regex> =
        Regex("\"(emoji_reaction_[a-z_]+)\":\\s*\"(.*)\"")
            .findAll(File("src/main/assets/emojis/en.json").readText())
            .associate { it.groupValues[1] to Regex(it.groupValues[2]) }

    private val named = mapOf(
        "❤️" to "heart", "👍" to "like", "👎" to "dislike",
        "😂" to "laugh", "‼️" to "exclamation", "❓" to "question_mark",
    )

    @Test
    fun everySmsChoiceReadsBackAsItsOwnReaction() {
        assertEquals(6, EmojiReactionRepository.SMS_CHOICES.size)
        EmojiReactionRepository.SMS_CHOICES.forEach { emoji ->
            val name = named.getValue(emoji)
            for (remove in listOf(false, true)) {
                val text = composeTapback(emoji, "  see you at 6 ", remove)
                val key = "emoji_reaction_ios_${name}_" + if (remove) "removed" else "added"
                val match = patterns.getValue(key).find(text)
                assertNotNull("$key did not match: $text", match)
                assertEquals("see you at 6", match!!.groupValues[1])
            }
        }
    }

    @Test
    fun anyOtherEmojiUsesTheGenericForm() {
        val added = patterns.getValue("emoji_reaction_ios_generic_added")
            .find(composeTapback("😮", "hello", remove = false))!!
        assertEquals(listOf("😮", "hello"), added.groupValues.drop(1))
        val removed = patterns.getValue("emoji_reaction_ios_generic_removed")
            .find(composeTapback("😮", "hello", remove = true))!!
        assertEquals(listOf("😮", "hello"), removed.groupValues.drop(1))
    }

    @Test
    fun aQuoteInsideTheMessageSurvives() {
        val text = composeTapback("👍", "she said “no” twice", remove = false)
        assertEquals(
            "she said “no” twice",
            patterns.getValue("emoji_reaction_ios_like_added").find(text)!!.groupValues[1]
        )
    }
}
