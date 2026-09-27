package com.wanderwildwood.kotozute.signalstore

import com.wanderwildwood.kotozute.signalstore.BodyStyles.Mention
import com.wanderwildwood.kotozute.signalstore.BodyStyles.Range
import com.wanderwildwood.kotozute.signalstore.BodyStyles.Style
import org.junit.Assert.assertEquals
import org.junit.Test

class BodyStylesTest {

    private val obj = "￼"

    @Test
    fun `styles without mentions stay where they are`() {
        val (text, styles) = BodyStyles.apply("hello world", emptyList(), listOf(Range(6, 5, Style.BOLD)))
        assertEquals("hello world", text)
        assertEquals(listOf(Range(6, 5, Style.BOLD)), styles)
    }

    @Test
    fun `a style after a mention moves by the name's extra length`() {
        // "hi ￼ see this" -> "hi @Sam see this"; "this" was at 9, the name adds 3.
        val body = "hi $obj see this"
        val (text, styles) = BodyStyles.apply(
            body, listOf(Mention(3, 1, "@Sam")), listOf(Range(9, 4, Style.ITALIC))
        )
        assertEquals("hi @Sam see this", text)
        assertEquals("this", text.substring(styles[0].start, styles[0].start + styles[0].length))
    }

    @Test
    fun `a style around a mention takes in the whole name`() {
        val body = "a $obj b"
        val (text, styles) = BodyStyles.apply(body, listOf(Mention(2, 1, "@Robin")), listOf(Range(0, 5, Style.BOLD)))
        assertEquals("a @Robin b", text)
        assertEquals(Range(0, text.length, Style.BOLD), styles[0])
    }

    @Test
    fun `a style ending just after a mention ends after the whole name`() {
        val body = "x${obj}y"
        val (text, styles) = BodyStyles.apply(body, listOf(Mention(1, 1, "@Al")), listOf(Range(0, 2, Style.SPOILER)))
        assertEquals("x@Aly", text)
        assertEquals(Range(0, 4, Style.SPOILER), styles[0])
    }

    @Test
    fun `out of bounds and empty ranges are dropped`() {
        val (_, styles) = BodyStyles.apply(
            "short", emptyList(),
            listOf(Range(3, 10, Style.BOLD), Range(-1, 2, Style.BOLD), Range(1, 0, Style.BOLD), Range(1, 2, Style.MONOSPACE))
        )
        assertEquals(listOf(Range(1, 2, Style.MONOSPACE)), styles)
    }

    @Test
    fun `overlapping styles are all kept`() {
        val (_, styles) = BodyStyles.apply(
            "abcdef", emptyList(), listOf(Range(0, 4, Style.BOLD), Range(2, 4, Style.ITALIC))
        )
        assertEquals(2, styles.size)
    }

    @Test
    fun `encode and decode round trip`() {
        val ranges = listOf(Range(0, 3, Style.BOLD), Range(4, 2, Style.SPOILER), Range(1, 1, Style.STRIKETHROUGH))
        assertEquals(ranges, BodyStyles.decode(BodyStyles.encode(ranges)))
        assertEquals("", BodyStyles.encode(emptyList()))
        assertEquals(emptyList<Range>(), BodyStyles.decode("not json"))
    }

    @Test
    fun `spoilers are blanked but spaces kept`() {
        val hidden = BodyStyles.withSpoilersHidden("the end is near", listOf(Range(4, 11, Style.SPOILER), Range(0, 3, Style.BOLD)))
        assertEquals("the ${"█".repeat(3)} ${"█".repeat(2)} ${"█".repeat(4)}", hidden)
    }
}
