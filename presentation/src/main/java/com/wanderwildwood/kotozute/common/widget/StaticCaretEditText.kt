package com.wanderwildwood.kotozute.common.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText

/**
 * A text field whose caret does not blink.
 *
 * The platform blinks the caret from a timer, twice a second, for as long as a field has
 * focus, and nothing but hiding it stops that. On an e-ink panel each blink is a repaint, and
 * a composer left open redraws for as long as it sits there. So the platform's caret is
 * switched off and the same caret is drawn here, as a steady ink bar at the cursor.
 *
 * The same class as Whereabouts' `StaticCaretEditText`. [QkEditText] is one of these, so every
 * field in the layouts has it; the fields built in code for dialogs use this directly.
 */
open class StaticCaretEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle,
) : AppCompatEditText(context, attrs, defStyleAttr) {

    private val caret = Paint().apply { color = Color.BLACK }
    private val caretWidth = 2f * resources.displayMetrics.density

    /**
     * Whether the caret is wanted, as the platform's would have been. Callers keep their own
     * rules -- the composer hides it until somebody writes (`showCursorWhenWriting`) -- and
     * only the drawing changes.
     */
    private var caretWanted = true

    init {
        // Read here rather than trusted from the constructor: TextView applies the attribute
        // before this class's fields exist, and the line above would then overwrite it.
        caretWanted = context.obtainStyledAttributes(
            attrs, intArrayOf(android.R.attr.cursorVisible), defStyleAttr, 0
        ).run { getBoolean(0, true).also { recycle() } }
        super.setCursorVisible(false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val layout = layout ?: return
        if (!caretWanted || !isFocused || selectionStart < 0 || selectionStart != selectionEnd) return
        val offset = selectionStart
        val line = layout.getLineForOffset(offset)
        val x = totalPaddingLeft + layout.getPrimaryHorizontal(offset)
        // totalPaddingTop, not extendedPaddingTop: it includes the offset gravity gives the
        // text, so a field that centres its line gets its caret on the line, not above it.
        val top = totalPaddingTop + layout.getLineTop(line)
        val bottom = totalPaddingTop + layout.getLineBottom(line)
        canvas.drawRect(x, top.toFloat(), x + caretWidth, bottom.toFloat(), caret)
    }

    // The caret moves without the text changing, and with the platform's caret off nothing
    // else asks for the redraw that moves this one. One frame per move, none while still.
    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        invalidate()
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        invalidate()
    }

    // The platform's caret stays off whatever is asked: leaving it hidden is the point. What
    // was asked decides whether the steady one is drawn.
    override fun setCursorVisible(visible: Boolean) {
        super.setCursorVisible(false)
        if (caretWanted != visible) {
            caretWanted = visible
            invalidate()
        }
    }
}
