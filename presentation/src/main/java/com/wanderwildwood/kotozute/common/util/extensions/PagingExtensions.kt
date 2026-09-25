package com.wanderwildwood.kotozute.common.util.extensions

import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * A swipe moves the list one screen, and then stops.
 *
 * Mudita's own lists do not scroll continuously on this panel. MMD takes the scrolling away
 * from the list entirely and steps a fixed four rows per swipe, because a screen that redraws
 * in full renders inertia as a smear and spends the battery drawing it. Neither list here can
 * use MMD's component — one is a RecyclerView shared by the conversations and the search
 * results with swipe-to-archive hung off it, the other is a thread of bubbles whose heights
 * are not known until their pictures have decoded — so both take the behaviour instead.
 *
 * A screen rather than four rows, because four rows is MMD's measure of its own lists and not
 * of these. Nothing is skipped: the page you arrive at begins where the last one ended.
 *
 * **The last line of the old page becomes the first line of the new one.** After the jump,
 * whichever row the page landed part-way through is pulled fully into view, which costs a row
 * of the leap and buys two things: a top edge that is always a row's edge, and one line of
 * overlap to read forward from. On a thread of messages that overlap is the sentence you were
 * halfway through.
 *
 * Only plainly vertical gestures are claimed, so a sideways swipe still reaches whatever is
 * listening underneath — archiving a conversation, in the one place that has it. Once a page
 * has turned the rest of the drag is swallowed: one swipe is one page, however far the finger
 * keeps travelling.
 */
fun RecyclerView.turnsAPageOnSwipe() {
    // Letting go stops the list rather than throwing it across four screens of history.
    onFlingListener = object : RecyclerView.OnFlingListener() {
        override fun onFling(velocityX: Int, velocityY: Int) = true
    }

    val slop = ViewConfiguration.get(context).scaledTouchSlop
    addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var turned = false

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    turned = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (turned) return true
                    val dy = e.y - downY
                    val dx = e.x - downX
                    if (abs(dy) > slop && abs(dy) > abs(dx)) {
                        turned = true
                        rv.turnPage(forward = dy < 0)
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) = Unit
        override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) = Unit
    })
}

/**
 * One screen on, landing on a row's edge. Back is [turnPageBack], which cannot use the same
 * pull.
 *
 * By pixels rather than by item, which is what makes this safe on a thread: a bubble taller
 * than the screen simply takes two pages, and a picture that has not finished decoding cannot
 * throw the count off, because nothing here is counting.
 */
private fun RecyclerView.turnPage(forward: Boolean) {
    val page = height - paddingTop - paddingBottom
    if (page <= 0) return
    if (!forward) {
        turnPageBack(page)
        return
    }
    scrollBy(0, page)

    // Two places the row-edge rule has to give way, both of them because the pull that buys the
    // edge costs a row, and here the list cannot afford one.
    //
    // The end is the first. A page turn near the bottom scrolls only as far as there is list
    // left, and the pull would push the last message's final lines back under the compose bar —
    // where the next swipe cannot reach them either, because it lands in exactly the same
    // place. The last page opens mid-row and shows the end of the thread.
    if (!canScrollVertically(if (forward) 1 else -1)) return

    val first = getChildAt(0) ?: return

    // A bubble taller than the screen is the second: a photo, or a message of some length. It
    // cannot be brought fully into view at any scroll position, and pulling at it hands back
    // nearly the whole page — a swipe that moves the thread a pixel, and moves it that same
    // pixel every time after. This is the bubble the doc above says takes two pages, and this
    // is what lets it: the first of them ends part-way down.
    if (first.height > page) return

    // Otherwise whatever row the page landed part-way through comes fully into view, so the
    // page opens on a whole one and carries a line of overlap from the page before. Where its
    // top has to land is not the same in both lists: the thread clips to its padding, so a row
    // sitting at the view's top edge has its first line cut off and must go a padding lower;
    // the conversation list does not clip, so the same padding is a strip the row above shows
    // through, and the row belongs at the edge itself.
    scrollBy(0, first.top - if (clipToPadding) paddingTop else 0)
}

/**
 * One screen back, losing nothing.
 *
 * ⚠ Not the forward turn run backwards, which is what this was, and which lost messages. The
 * forward turn pulls a part-cut top row into view by scrolling back a little -- over rows
 * already read. Backwards, the same pull scrolls *further up*, and what it pushes off the
 * bottom is the one row this page exists to show: the row just above the page before. Paging
 * up through a thread skipped a message every page or two, and they were there again on the
 * way down (forum: "some messages get lost in the scroll").
 *
 * So backwards the overlap is anchored at the bottom: the row that opened the page before
 * lands whole at the bottom of this one, the line to read back from. The top row is pulled
 * into view only when that costs no more than the overlap row itself -- something already
 * read. Otherwise the page opens mid-row, and the next page back shows that row whole.
 */
private fun RecyclerView.turnPageBack(page: Int) {
    val topEdge = if (clipToPadding) paddingTop else 0
    val bottomEdge = height - paddingBottom

    // The row this page opened on: the first one showing below the top edge.
    val opener = (0 until childCount).map { getChildAt(it) }.firstOrNull { it.bottom > topEdge }
    if (opener == null || opener.height > page) {
        scrollBy(0, -page)
        return
    }
    val overlap = opener.height
    // Its bottom to the bottom edge: everything above it on the new page is new.
    scrollBy(0, opener.bottom - bottomEdge)

    // At the start of the list there is nothing above to make room for.
    if (!canScrollVertically(-1)) return

    val first = getChildAt(0) ?: return
    val cut = topEdge - first.top
    if (cut in 1..overlap && first.height <= page) scrollBy(0, -cut)
}

