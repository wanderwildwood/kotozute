package com.wanderwildwood.kotozute.common.widget

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.wanderwildwood.kotozute.R

/**
 * A short row of emoji to react with, and a way to take one back.
 *
 * Six, not a picker. A reaction is a quick thing and a grid of a thousand glyphs on a 480px
 * e-ink screen is not quick -- the phone's own emoji panel exists for the composer, where
 * somebody is actually writing. Shared by both rails; each passes its own six, because
 * Signal's set and the set an SMS can carry are not the same.
 */
object ReactionPicker {

    fun show(
        context: Context,
        choices: List<String>,
        mine: String,
        onPick: (emoji: String, remove: Boolean) -> Unit,
    ) {
        // A row, not a list. Six list items would be six full-width rows on a 480px screen
        // to hold six glyphs, and the eye has to travel the height of the display to read a
        // choice that fits on one line.
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(16), dp(8), dp(16))
        }
        val dialog = AlertDialog.Builder(context).setView(row).show()
        choices.forEach { emoji ->
            val cell = QkTextView(context).apply {
                text = emoji
                textSize = 26f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                // The one already given is ringed, and tapping it takes it back -- which is
                // what tapping it again does in Signal and on an iPhone.
                // ⚠ Black, not `rounded_rectangle_outline_4dp`: that one is a white stroke,
                // and on this white dialog the ring was never visible.
                if (emoji == mine) {
                    setBackgroundResource(R.drawable.rounded_rectangle_outline)
                }
                setOnClickListener {
                    dialog.dismiss()
                    onPick(emoji, emoji == mine)
                }
            }
            row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }
}
