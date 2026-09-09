package com.wanderwildwood.kotozute.common.widget

import com.wanderwildwood.kotozute.common.widget.PageTurn.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where a page turn lands.
 *
 * Every case here is something that actually went wrong on the phone. The behaviour was built
 * inside a View, where none of it could be checked without a device, and it cost an afternoon
 * of build-install-swipe to find faults that are three lines of integer reasoning each.
 */
class PageTurnTest {

    /** Eight rows of 100, in a viewport of 300, with 150 of slack past the end. */
    private val rows = (0 until 8).map { Row(top = it * 100, height = 100) }
    private val page = 300
    private val contentHeight = 800
    private val maxScroll = contentHeight + 150 - page   // 650

    private fun turn(from: Int, forward: Boolean = true) =
        PageTurn.target(from, page, maxScroll, contentHeight, rows, forward)

    @Test
    fun `a page forward lands on a row edge, not part-way through one`() {
        // 0 + 300 falls exactly on a boundary here, so take a case that does not: from 50 the
        // raw landing is 350, inside the row that starts at 300.
        assertEquals(300, turn(from = 50))
    }

    @Test
    fun `a page back also lands on a row edge`() {
        // 350 back one page is 50, which is inside the row starting at 0, so the page opens on
        // that row rather than half way down it.
        assertEquals(0, turn(from = 350, forward = false))
        // And from further down, on the row the raw landing fell inside.
        assertEquals(300, turn(from = 650, forward = false))
    }

    @Test
    fun `the page carries the row it landed inside, so nothing is skipped`() {
        // The row spanning the new top is pulled fully into view rather than clipped: that row
        // is the overlap between one page and the next, and the reason a sentence broken
        // across a page boundary can still be read.
        val landing = turn(from = 250)!!
        val rowAtTop = rows.first { it.top == landing }
        assertEquals(landing, rowAtTop.top)
    }

    @Test
    fun `the last page is terminal`() {
        // The bug this exists to prevent: at the end, moving anyway ran into the slack, was
        // pulled back to a row edge, and the next swipe overshot and was pulled back to a
        // *different* row -- so the last two positions alternated for as long as you swiped,
        // and one swipe forward could travel backwards.
        assertNull(turn(from = 500))
        assertNull(turn(from = 650))
    }

    @Test
    fun `the top is terminal going back`() {
        assertNull(turn(from = 0, forward = false))
    }

    @Test
    fun `a row taller than the screen takes two pages instead of trapping the scroll`() {
        val tall = listOf(Row(0, 100), Row(100, 900), Row(1000, 100))
        // Landing inside the tall row must not be pulled back to its top: that would hand back
        // nearly the whole page, so the same swipe would move the screen the same few pixels
        // every time and the row could never be passed.
        val landing = PageTurn.target(
            current = 150, page = page, maxScroll = 900,
            contentHeight = 1100, rows = tall, forward = true
        )
        assertEquals(450, landing)
    }

    @Test
    fun `a page never scrolls past the end of what exists`() {
        val landing = turn(from = 400)
        if (landing != null) assert(landing <= maxScroll) { "scrolled past the end: $landing" }
    }

    @Test
    fun `no rows at all is not a crash`() {
        assertEquals(
            300,
            PageTurn.target(0, page, maxScroll, contentHeight, emptyList(), forward = true)
        )
    }

    @Test
    fun `a viewport with no height does nothing`() {
        // Asked before the view has been measured, which happens.
        assertNull(PageTurn.target(0, 0, maxScroll, contentHeight, rows, forward = true))
    }
}
