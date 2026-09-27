package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "@Name" in a group message becoming the mention Signal sends: a placeholder in the body and
 * a range naming the person. Wrong either way is bad -- a missed mention is a notification
 * nobody gets, and a false one pings somebody who was not addressed.
 */
class OutgoingMentionsTest {

    private val ada = "aci-ada"
    private val adaW = "aci-ada-w"
    private val tom = "aci-tom"
    private val names = mapOf(ada to "Ada", adaW to "Ada Whitlock", tom to "Tomas Reyes")

    @Test
    fun `a named member becomes a mention`() {
        val e = OutgoingMentions.encode("hi @Tomas Reyes, gate code?", names)
        assertEquals("hi ￼, gate code?", e.body)
        assertEquals(listOf(OutgoingMentions.Mention(tom, 3, 1)), e.mentions)
    }

    @Test
    fun `the longest name wins`() {
        val e = OutgoingMentions.encode("@Ada Whitlock and @Ada", names)
        assertEquals("￼ and ￼", e.body)
        assertEquals(listOf(OutgoingMentions.Mention(adaW, 0, 1), OutgoingMentions.Mention(ada, 6, 1)), e.mentions)
    }

    @Test
    fun `an address or a longer word is not a mention`() {
        assertTrue(OutgoingMentions.encode("mail ada@example.org", names).mentions.isEmpty())
        assertTrue(OutgoingMentions.encode("@Adamant is not Ada", names).mentions.isEmpty())
        assertEquals("@Nobody", OutgoingMentions.encode("@Nobody", names).body)
    }

    @Test
    fun `positions count what comes before, emoji included`() {
        // U+1F344 is two UTF-16 units, which is how Signal counts a range's start.
        val e = OutgoingMentions.encode("🍄 @Ada", names)
        assertEquals(listOf(OutgoingMentions.Mention(ada, 3, 1)), e.mentions)
    }

    @Test
    fun `styles move with the text a mention shortens`() {
        // "hi @Ada see this": "this" is at 12; the mention takes "@Ada" (4) down to 1.
        val e = OutgoingMentions.encode(
            "hi @Ada see this", names,
            listOf(BodyStyles.Range(12, 4, BodyStyles.Style.BOLD))
        )
        val r = e.styles.single()
        assertEquals("this", e.body.substring(r.start, r.start + r.length))
    }

    @Test
    fun `a style over part of a name takes in the whole mention`() {
        // Bold over "hi @Ad": ends inside the name, so it ends after the placeholder.
        val e = OutgoingMentions.encode("hi @Ada x", names, listOf(BodyStyles.Range(0, 6, BodyStyles.Style.BOLD)))
        assertEquals(listOf(BodyStyles.Range(0, 4, BodyStyles.Style.BOLD)), e.styles)
    }

    @Test
    fun `styles pass through untouched when nothing is mentioned`() {
        val styles = listOf(BodyStyles.Range(0, 2, BodyStyles.Style.ITALIC))
        assertEquals(styles, OutgoingMentions.encode("hi there", names, styles).styles)
    }
}
