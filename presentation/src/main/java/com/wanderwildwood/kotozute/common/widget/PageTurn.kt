package com.wanderwildwood.kotozute.common.widget

/**
 * Where a page turn should land.
 *
 * Pulled out of [PagingScrollView] because every mistake this behaviour made was arithmetic,
 * and none of it could be tested while it lived inside a View. In one afternoon it landed a
 * page through the middle of a line of text, and then — after the first fix — alternated
 * between two positions for as long as you kept swiping, so a swipe forward could travel
 * backwards. Both are three lines of reasoning about integers, and both are now cases below
 * rather than something to rediscover on a phone.
 *
 * The view keeps the parts that need a view: measuring, finding the rows, and scrolling.
 */
internal object PageTurn {

    /** One row of the column, in the scrolled content's own coordinates. */
    data class Row(val top: Int, val height: Int)

    /**
     * @param current      where the column is scrolled to now
     * @param page         one screen of content
     * @param maxScroll    the furthest the view can scroll, including any slack past the end
     * @param contentHeight the column's real height, not counting slack
     * @param rows         the visible rows, in order
     * @param forward      down the column, or back up it
     * @return where to scroll to, or null to stay exactly where we are
     */
    fun target(
        current: Int,
        page: Int,
        maxScroll: Int,
        contentHeight: Int,
        rows: List<Row>,
        forward: Boolean
    ): Int? {
        if (page <= 0) return null

        // The ends are terminal. A page that already reaches the bottom of the column has
        // nowhere to go, and moving anyway is what made the last page flicker: the scroll ran
        // into the slack, was pulled back to a row edge, and the next swipe overshot and was
        // pulled back to a different row.
        if (forward && current + page >= contentHeight) return null
        if (!forward && current <= 0) return null

        val raw = (current + if (forward) page else -page).coerceIn(0, maxScroll)

        // Whatever row the page landed part-way through comes fully into view, so a page opens
        // on a whole row and carries a line of overlap from the page before.
        val straddling = rows.firstOrNull { it.top < raw && it.top + it.height > raw }
            ?: return raw

        // A row taller than the screen cannot be brought fully into view at any scroll
        // position, and pulling at it would hand back nearly the whole page. That row is the
        // one that takes two pages, and this page ends part-way down it.
        return if (straddling.height <= page) straddling.top else raw
    }
}
