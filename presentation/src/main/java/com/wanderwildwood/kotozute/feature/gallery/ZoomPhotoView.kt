package com.wanderwildwood.kotozute.feature.gallery

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import com.github.chrisbanes.photoview.PhotoView
import kotlin.math.max

/**
 * PhotoView with this app's rules for a picture shown full size:
 *  - pinch and spread to zoom, one finger to move it once enlarged (the library's own);
 *  - a double tap jumps to one picture pixel per screen pixel under the finger, the next goes
 *    back to fitted -- see [doubleTapTarget];
 *  - nothing animates, and a moved picture stops where the finger stops (no fling), as on the
 *    rest of this screen.
 * A single tap is still the library's, so whatever the page's click listener does is unchanged.
 */
class ZoomPhotoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : PhotoView(context, attrs, defStyle) {

    init {
        setScaleLevels()
        // The library's listener does the single tap and the double tap together, so both are
        // replaced: the single tap is still the page's click listener, nothing else.
        attacher.setOnDoubleTapListener(object : GestureDetector.OnDoubleTapListener {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val click = clicked ?: return false
                click.onClick(this@ZoomPhotoView)
                return true
            }
            override fun onDoubleTapEvent(e: MotionEvent) = false
            override fun onDoubleTap(e: MotionEvent): Boolean {
                doubleTap(e.x, e.y)
                return true
            }
        })
    }

    private var clicked: OnClickListener? = null

    override fun setOnClickListener(l: OnClickListener?) {
        clicked = l
        super.setOnClickListener(l)
    }

    /** Fitted is the smallest, and the library's middle stop is the same, so a tap has two states. */
    private fun setScaleLevels(max: Float = 3f) {
        // The public setter will not take a middle level equal to the others, and two levels are
        // wanted, so the fields are set directly.
        for ((name, value) in listOf("mMinScale" to 1f, "mMidScale" to 1f, "mMaxScale" to max)) {
            attacher.javaClass.getDeclaredField(name).run {
                isAccessible = true
                setFloat(attacher, value)
            }
        }
    }

    private fun doubleTap(x: Float, y: Float) {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        raiseLimit(d)
        attacher.setScale(
            doubleTapTarget(attacher.scale, width.toFloat(), height.toFloat(), d.intrinsicWidth, d.intrinsicHeight),
            x, y, false,
        )
    }

    /** Pinching can reach 1:1 too, however large the picture is. */
    private fun raiseLimit(d: Drawable) {
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        setScaleLevels(max(3f, oneToOneScale(width.toFloat(), height.toFloat(), d.intrinsicWidth, d.intrinsicHeight)))
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        drawable?.let(::raiseLimit)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        drawable?.let(::raiseLimit)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(event)
        // The library starts a fling when the finger lifts. Stopped at once, the picture stays where it was left.
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            runCatching {
                attacher.javaClass.getDeclaredMethod("cancelFling").run {
                    isAccessible = true
                    invoke(attacher)
                }
            }
        }
        return handled
    }
}
