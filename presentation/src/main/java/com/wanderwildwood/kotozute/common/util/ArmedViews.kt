package com.wanderwildwood.kotozute.common.util

import android.view.MotionEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.WeakHashMap

/*
 * The same "tap again" rule as Armed.kt, for Views apps (and Views screens inside Compose apps).
 * No timer: an armed row stays armed until the person does something else (a tap or drag
 * anywhere else in the window, Back, the activity pausing).
 *
 *     val watch = ArmedWatch.of(activity)            // after setContentView
 *     row.setOnClickListener {
 *         if (watch.isArmed(disarmRow)) { watch.done(disarmRow); doIt() }
 *         else { showArmed(); watch.arm(disarmRow) }
 *     }
 *     val disarmRow: () -> Unit = { showPlain() }     // one stable lambda per row
 */
class ArmedWatch private constructor(activity: ComponentActivity) {
    private var live: (() -> Unit)? = null
    private var atDown: (() -> Unit)? = null
    private val window: Window = activity.window
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = disarm()
    }

    init {
        activity.onBackPressedDispatcher.addCallback(activity, back)
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) disarm()
        })
        val inner = window.callback
        window.callback = object : Window.Callback by inner {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) atDown = live
                val handled = inner.dispatchTouchEvent(event)
                val end = event.actionMasked
                if (end == MotionEvent.ACTION_UP || end == MotionEvent.ACTION_CANCEL) {
                    val began = atDown
                    atDown = null
                    // A View posts its click while handling ACTION_UP (just above), so this
                    // check, posted after it, sees the row's own second tap as that.
                    if (began != null) window.decorView.post {
                        if (began === live) disarm()
                    }
                }
                return handled
            }
        }
    }

    fun isArmed(row: () -> Unit): Boolean = live === row

    /** Arms [row], disarming any other first. [row] is called to put the row back when it disarms. */
    fun arm(row: () -> Unit) {
        if (live === row) return
        val other = live
        live = row
        back.isEnabled = true
        other?.invoke()
    }

    /** The row's second tap: it is no longer armed, and it redraws itself. */
    fun done(row: () -> Unit) {
        if (live === row) {
            live = null
            back.isEnabled = false
        }
    }

    /** Disarms whichever row is armed and lets it redraw. */
    fun disarm() {
        val row = live ?: return
        live = null
        back.isEnabled = false
        row()
    }

    companion object {
        private val watches = WeakHashMap<ComponentActivity, ArmedWatch>()

        fun of(activity: ComponentActivity): ArmedWatch =
            watches.getOrPut(activity) { ArmedWatch(activity) }
    }
}
