package com.wanderwildwood.kotozute.common.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ScrollView
import kotlin.math.abs

/**
 * A settings screen that turns pages instead of scrolling.
 *
 * The lists already do this — see `RecyclerView.turnsAPageOnSwipe` — for the reason MMD does
 * it: a panel that redraws in full renders inertia as a smear, and spends the battery drawing
 * it. The menus were left behind because they are not lists. They are a `ScrollView` holding
 * one long column of rows, so there is no item touch listener to hang the behaviour off and
 * no adapter to ask how tall anything is.
 *
 * This is the same behaviour built the other way round: intercept the drag before the
 * ScrollView starts scrolling with it, move exactly one screen, and swallow whatever is left
 * of the gesture. One swipe is one page however far the finger keeps going.
 *
 * Taps are untouched. A drag is only claimed once it passes the touch slop *and* is plainly
 * more vertical than horizontal, so a row's own click still fires and a sideways gesture still
 * reaches whatever is listening underneath.
 */
class PagingScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private var downX = 0f
    private var downY = 0f
    private var turned = false

    /**
     * Blank space past the end of the column, so the last page can begin on a row edge.
     *
     * Without it the last page is the one place a page cannot land on a row edge: the scroll
     * stops when the content runs out, wherever that leaves the top, and on a long settings
     * section that left a row cut through the middle of a line under the toolbar. Aligning
     * that page without the slack would push the final rows off the screen with no way back
     * to them, because the next swipe lands in the same place and is pulled up again.
     *
     * It is a fixed measure, deliberately. The first attempt worked it out at layout time —
     * exactly enough to end on a boundary — and recomputing on every layout moved the scroll
     * range underneath the scroll position: pages flipped between two places, and one swipe
     * forward could travel backwards. A constant cannot do that.
     *
     * The only thing it must satisfy is **slack ≥ the tallest row**, which is what guarantees
     * that pulling the last page up to a row edge still leaves every real row on screen. The
     * tallest row in these menus is a three-line summary, a little over 80dp; 144dp clears it
     * and is still far short of a page, which is what keeps a row on screen to align to.
     */
    private val slack = (SLACK_DP * resources.displayMetrics.density).toInt()

    init {
        // The slack is scrollable space, not a margin around the content, so the column has to
        // be allowed to draw through it rather than be clipped to it.
        clipToPadding = false
        setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom + slack)
    }

    /**
     * Letting go stops the page rather than throwing the column across several screens of
     * settings. Without this the ScrollView's own fling survives the intercept and undoes
     * the whole point.
     */
    override fun fling(velocityY: Int) = Unit

    /**
     * The gesture, wherever it arrives.
     *
     * Both entry points feed this, and they have to, because which one sees a given swipe
     * depends on what is under the finger. A drag that starts on a row is dispatched to that
     * child, so this view is asked to intercept and sees the MOVE there. A drag that starts
     * on a gap between rows is consumed by nobody, and `ViewGroup` then stops consulting
     * `onInterceptTouchEvent` altogether and hands events straight to `onTouchEvent`. Watching
     * only the intercept path meant a swipe over a row turned a page and a swipe two pixels
     * lower scrolled continuously, which was the whole behaviour this class exists to remove.
     *
     * @return true once the page has turned and the rest of the drag should be swallowed.
     */
    private fun track(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                turned = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (turned) return true
                val dy = ev.y - downY
                val dx = ev.x - downX
                if (abs(dy) > slop && abs(dy) > abs(dx)) {
                    turned = true
                    turnPage(forward = dy < 0)
                    return true
                }
            }
        }
        return turned
    }

    // Never intercepts the DOWN, so a row still gets its tap; claims the gesture only once a
    // page has actually turned.
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = track(ev)

    /**
     * Always consumed, and deliberately never passed to `super`.
     *
     * The base class is what scrolls continuously, so letting it see the drag at all would
     * reintroduce the smear. Everything this view does to the scroll position it does in
     * [turnPage].
     */
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        return true
    }

    /**
     * One screen on, or one back, landing on a row's edge.
     *
     * By pixels, then corrected to a row, for the same reason the list version is: a row
     * taller than the screen — a long explanatory summary, say — simply takes two pages, and
     * nothing here has to know how tall anything is in advance.
     */
    private fun turnPage(forward: Boolean) {
        // The slack is not chrome and must not shrink the page, or every turn would fall a
        // little short of a screen and slowly lose its place.
        val page = height - paddingTop - (paddingBottom - slack)
        if (page <= 0) return

        // The ends are terminal, and have to be said explicitly.
        //
        // A page that already reaches the bottom of the column has nowhere to go, and moving
        // anyway is what made the last page flicker: the scroll ran into the slack, was pulled
        // back to a row edge, and the next swipe overshot and was pulled back to a *different*
        // row -- so one swipe forward could travel backwards, and the two positions alternated
        // for as long as you kept swiping. Refusing the move outright is both correct and the
        // only version of this that settles.
        val column = getChildAt(0) ?: return
        if (forward && scrollY + page >= column.height) return
        if (!forward && scrollY <= 0) return

        scrollBy(0, if (forward) page else -page)

        val top = scrollY
        var container = getChildAt(0) as? ViewGroup ?: return
        var offset = 0

        // Descend to the row, rather than assuming the scroll view's own child is one.
        //
        // These screens do not all nest the same way: the settings screen keeps every section
        // in one layout and swaps them in place, so one level down is a wrapper holding six
        // sections of which five are GONE. Looking only there found a single child taller than
        // any page, which straddles every boundary and is far too tall to pull into view, so
        // no page ever aligned to anything. Walking down finds the row at whatever depth it is.
        while (true) {
            var found: View? = null
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                if (child.visibility == GONE) continue
                val childTop = offset + child.top
                if (childTop < top && childTop + child.height > top) {
                    found = child
                    break
                }
            }

            // Nothing straddles the top: the page already begins on an edge, which is what
            // happens at the very top of the column and out in the slack past its end.
            val child = found ?: return
            val childTop = offset + child.top

            // Whatever row the page landed part-way through comes fully into view, so a page
            // opens on a whole row and carries a line of overlap from the page before. The
            // slack past the end of the column is what lets this happen on the last page too,
            // without pushing the final rows out of reach.
            if (child.height <= page) {
                scrollTo(0, childTop)
                return
            }

            // Taller than the screen. If it is a container, the row is inside it; if it is a
            // single view that simply does not fit, it is the one that takes two pages, and
            // this page ends part-way down it.
            if (child is ViewGroup && child.childCount > 0) {
                container = child
                offset = childTop
            } else {
                return
            }
        }
    }

    private companion object {
        /** See [slack]: must clear the tallest row, and stay well short of a page. */
        const val SLACK_DP = 144f
    }
}
