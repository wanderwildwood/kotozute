package com.wanderwildwood.kotozute.common.util

import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.view.MotionEvent
import android.view.Window
import androidx.activity.ComponentActivity

/*
 * The app's side of ArmedViews.kt (shared-armed, kept byte for byte with the other apps).
 * A "tap again" row stays armed, with no timer, until the person does something else.
 */

/** The armed-row watch of the activity behind [this] context, or null outside one. */
fun Context.armedWatch(): ArmedWatch? {
    var c: Context? = this
    while (c != null) {
        if (c is ComponentActivity) return ArmedWatch.of(c)
        c = (c as? ContextWrapper)?.baseContext
    }
    return null
}

/**
 * The same rule for one armed button inside a dialog, whose window the activity's watch does
 * not see: a tap or drag anywhere else in the dialog disarms it, and so does the dialog going
 * away. Back closes the dialog, which takes the armed button with it.
 */
class DialogArmed(dialog: Dialog, private val disarmed: () -> Unit) {
    var armed = false
        private set
    private var atDown = false

    init {
        val window = dialog.window
        if (window != null) {
            val inner = window.callback
            window.callback = object : Window.Callback by inner {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) atDown = armed
                    val handled = inner.dispatchTouchEvent(event)
                    val end = event.actionMasked
                    if ((end == MotionEvent.ACTION_UP || end == MotionEvent.ACTION_CANCEL) && atDown) {
                        atDown = false
                        // Posted after the button's own click, so its second tap counts as that.
                        window.decorView.post { if (armed) disarm() }
                    }
                    return handled
                }
            }
        }
    }

    fun arm() { armed = true }

    /** The second tap: no longer armed. */
    fun done() { armed = false }

    fun disarm() {
        if (!armed) return
        armed = false
        disarmed()
    }
}
