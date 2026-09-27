package com.wanderwildwood.kotozute.feature.signal

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View
import com.wanderwildwood.kotozute.signalstore.BodyStyles

/**
 * A message's bold, italic, strikethrough, monospace and spoilers, drawn.
 *
 * Upstream's `MessageStyler.style`, as spans on a TextView. A spoiler is a solid bar in the
 * text's own colour, so it reads the same in a dark bubble and a light one, and a tap on it
 * reveals the message's spoilers -- as Signal's does. Nothing is animated: upstream's shimmer is
 * a redraw per frame, which e-ink cannot show and would only flash.
 */
object MessageStyles {

    fun apply(
        text: CharSequence,
        stylesJson: String,
        textColor: Int,
        revealed: Boolean,
        onReveal: () -> Unit
    ): CharSequence {
        val ranges = BodyStyles.decode(stylesJson)
        if (ranges.isEmpty()) return text
        val out = SpannableString(text)
        ranges.forEach { r ->
            val end = minOf(r.start + r.length, out.length)
            if (r.start >= end) return@forEach
            when (r.style) {
                BodyStyles.Style.BOLD -> out.setSpan(StyleSpan(Typeface.BOLD), r.start, end, FLAGS)
                BodyStyles.Style.ITALIC -> out.setSpan(StyleSpan(Typeface.ITALIC), r.start, end, FLAGS)
                BodyStyles.Style.STRIKETHROUGH -> out.setSpan(StrikethroughSpan(), r.start, end, FLAGS)
                BodyStyles.Style.MONOSPACE -> out.setSpan(TypefaceSpan("monospace"), r.start, end, FLAGS)
                BodyStyles.Style.SPOILER -> if (!revealed) {
                    out.setSpan(BackgroundColorSpan(textColor), r.start, end, FLAGS)
                    out.setSpan(ForegroundColorSpan(textColor), r.start, end, FLAGS)
                    out.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) = onReveal()
                        // Not drawn as a link: the bar is what says there is something here.
                        override fun updateDrawState(ds: TextPaint) {}
                    }, r.start, end, FLAGS)
                }
            }
        }
        return out
    }

    /** Whether [stylesJson] hides anything, so the view knows to take taps on its text. */
    fun hasSpoiler(stylesJson: String): Boolean =
        BodyStyles.decode(stylesJson).any { it.style == BodyStyles.Style.SPOILER }

    private const val FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
}
