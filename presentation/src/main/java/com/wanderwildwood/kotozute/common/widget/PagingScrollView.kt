package com.wanderwildwood.kotozute.common.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
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
        val column = getChildAt(0) ?: return
        val (container, offset) = rowContainer() ?: return

        val rows = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .filter { it.visibility != GONE }
            .map { PageTurn.Row(top = offset + it.top, height = it.height) }

        val target = PageTurn.target(
            current = scrollY,
            page = page,
            maxScroll = (column.height + slack - page).coerceAtLeast(0),
            contentHeight = column.height,
            rows = rows,
            forward = forward
        ) ?: return

        scrollTo(0, target)
    }

    /**
     * The view whose children are the rows, and its top in this view's coordinates.
     *
     * Not always the scroll view's own child. The settings screen keeps every section in one
     * layout and swaps them in place, so one level down is a wrapper holding six sections of
     * which five are GONE — measuring there measures sections, not rows, and a page could
     * never align to anything. Descending while there is exactly one visible child that could
     * hold rows finds the section actually on screen, at whatever depth it sits.
     */
    private fun rowContainer(): Pair<ViewGroup, Int>? {
        var container = getChildAt(0) as? ViewGroup ?: return null
        var offset = 0
        while (true) {
            val visible = (0 until container.childCount)
                .map { container.getChildAt(it) }
                .filter { it.visibility != GONE }
            val only = visible.singleOrNull() as? ViewGroup ?: return container to offset
            if (only.childCount == 0) return container to offset
            offset += only.top
            container = only
        }
    }

    private companion object {
        /** See [slack]: must clear the tallest row, and stay well short of a page. */
        const val SLACK_DP = 144f
    }
}
