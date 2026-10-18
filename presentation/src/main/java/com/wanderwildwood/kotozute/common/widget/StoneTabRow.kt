package com.wanderwildwood.kotozute.common.widget

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.wanderwildwood.kotozute.util.Preferences

/**
 * The filter tabs in a top bar, sized as the shared StoneTabs.kt sizes them in every other app
 * here: bold labels at MMD's titleMedium (20sp), the chosen one a black stone with 14dp either
 * side, set from the left at their own widths. A row that would run past the room it has
 * steps down to titleSmall (18sp) with 8dp stones rather than cut a label short.
 *
 * The two larger Font size settings keep their larger labels; at those sizes the row may
 * still not fit, and the labels ellipsize as they always have.
 */
class StoneTabRow @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    /** One of Preferences.TEXT_SIZE_*. */
    var textSizePref: Int = Preferences.TEXT_SIZE_LARGE
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    private fun roomySp(): Float = when (textSizePref) {
        Preferences.TEXT_SIZE_LARGER -> 22f
        Preferences.TEXT_SIZE_SUPER -> 37f
        else -> 20f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val room = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val tabs = (0 until childCount).map { getChildAt(it) }
            .filter { it.visibility != View.GONE }
            .filterIsInstance<TextView>()
        if (tabs.isNotEmpty() && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val roomy = roomySp()
            val fits = needed(tabs, roomy, ROOMY_PAD_DP) <= room
            val size = if (fits) roomy else roomy - 2f
            val pad = dp(if (fits) ROOMY_PAD_DP else TIGHT_PAD_DP)
            tabs.forEach { tab ->
                if (tab.textSize != sp(size)) tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
                if (tab.paddingLeft != pad || tab.paddingRight != pad) {
                    tab.setPadding(pad, tab.paddingTop, pad, tab.paddingBottom)
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** What the row needs at [sp] with [padDp] stones: each label, its two sides, the gaps. */
    private fun needed(tabs: List<TextView>, sp: Float, padDp: Int): Int {
        val paint = android.text.TextPaint(tabs.first().paint).apply { textSize = sp(sp) }
        val text = tabs.sumOf { paint.measureText(it.text.toString()).toDouble() }.toInt()
        val gaps = tabs.drop(1).sumOf { (it.layoutParams as? MarginLayoutParams)?.marginStart ?: 0 }
        return text + tabs.size * 2 * dp(padDp) + gaps
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private companion object {
        const val ROOMY_PAD_DP = 14
        const val TIGHT_PAD_DP = 8
    }
}
