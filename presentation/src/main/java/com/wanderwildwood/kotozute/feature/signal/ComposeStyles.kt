package com.wanderwildwood.kotozute.feature.signal

import android.graphics.Typeface
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import com.wanderwildwood.kotozute.signalstore.BodyStyles

/**
 * Styles in the draft: select some text, pick Bold, and it goes out bold.
 *
 * Signal Android's `ComposeText` selection menu and `MessageStyler.toggleStyle` /
 * `clearStyling`, simplified to what the rules come to: picking a style over text that is all
 * that style already takes it off; otherwise it goes on, joined with any of the same style it
 * touches. One span class marks and draws every style, so what is sent is read back from
 * exactly what was shown.
 */
object ComposeStyles {

    /** A styled stretch of the draft. [shade] draws a spoiler, which is not hidden while writing. */
    class Span(val style: BodyStyles.Style, val shade: Int) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) {
            updateMeasureState(tp)
            when (style) {
                BodyStyles.Style.STRIKETHROUGH -> tp.isStrikeThruText = true
                BodyStyles.Style.SPOILER -> tp.bgColor = shade
                else -> Unit
            }
        }

        override fun updateMeasureState(tp: TextPaint) {
            when (style) {
                BodyStyles.Style.BOLD -> tp.isFakeBoldText = true
                BodyStyles.Style.ITALIC -> tp.textSkewX = -0.25f
                BodyStyles.Style.MONOSPACE -> tp.typeface = Typeface.MONOSPACE
                else -> Unit
            }
        }
    }

    /** Upstream's `SPAN_FLAGS`: typing at the end of a styled stretch carries the style on. */
    private const val FLAGS = Spanned.SPAN_EXCLUSIVE_INCLUSIVE

    fun toggle(text: Spannable, start: Int, end: Int, style: BodyStyles.Style, shade: Int) {
        if (start >= end) return
        val same = text.getSpans(start, end, Span::class.java).filter { it.style == style }
        val covering = same.firstOrNull { text.getSpanStart(it) <= start && text.getSpanEnd(it) >= end }
        if (covering != null) {
            // All of it is already this style: take it off the selection, keep either side.
            val s = text.getSpanStart(covering)
            val e = text.getSpanEnd(covering)
            text.removeSpan(covering)
            if (s < start) text.setSpan(Span(style, shade), s, start, FLAGS)
            if (end < e) text.setSpan(Span(style, shade), end, e, FLAGS)
            return
        }
        var from = start
        var to = end
        same.forEach {
            from = minOf(from, text.getSpanStart(it))
            to = maxOf(to, text.getSpanEnd(it))
            text.removeSpan(it)
        }
        text.setSpan(Span(style, shade), from, to, FLAGS)
    }

    fun hasStyling(text: Spanned, start: Int, end: Int): Boolean =
        start < end && text.getSpans(start, end, Span::class.java).any {
            text.getSpanStart(it) < end && text.getSpanEnd(it) > start
        }

    /** Every style off the selection; what lies either side keeps its own. */
    fun clear(text: Spannable, start: Int, end: Int) {
        text.getSpans(start, end, Span::class.java).forEach { span ->
            val s = text.getSpanStart(span)
            val e = text.getSpanEnd(span)
            if (e <= start || s >= end) return@forEach
            text.removeSpan(span)
            if (s < start) text.setSpan(Span(span.style, span.shade), s, start, FLAGS)
            if (end < e) text.setSpan(Span(span.style, span.shade), end, e, FLAGS)
        }
    }

    /**
     * The draft's styles over [body], which is the draft with [lead] characters trimmed off
     * the front and whatever trailed it off the end.
     */
    fun of(text: Spanned, lead: Int, body: String): List<BodyStyles.Range> =
        text.getSpans(0, text.length, Span::class.java).mapNotNull { span ->
            val s = (text.getSpanStart(span) - lead).coerceIn(0, body.length)
            val e = (text.getSpanEnd(span) - lead).coerceIn(0, body.length)
            if (e > s) BodyStyles.Range(s, e - s, span.style) else null
        }.sortedBy { it.start }
}
