/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.wanderwildwood.kotozute.common.util.extensions

import android.content.Context
import android.content.res.ColorStateList
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager

/**
 * Layout transitions are disabled outright on this fork: the Kompakt's e-ink panel
 * redraws far too slowly for them, and an animated reflow smears into visible ghosting
 * rather than reading as motion. Neutralised here at the shared setter rather than at
 * the five call sites, the same way Colors.theme() forces black in one place instead of
 * patching every consumer. The XML android:animateLayoutChanges attributes are a
 * separate mechanism handled by the framework, and are set false individually.
 */
var ViewGroup.animateLayoutChanges: Boolean
    get() = layoutTransition != null
    set(@Suppress("UNUSED_PARAMETER") value) {
        layoutTransition = null
    }

fun EditText.showKeyboard() {
    requestFocus()
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
}

fun EditText.hideKeyboard() {
    requestFocus()
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow(windowToken, 0)
}

fun ImageView.setTint(color: Int?) {
    imageTintList =
        if (color == null) null
        else ColorStateList.valueOf(color)
}

fun TextView.setTint(color: Int?) {
    foregroundTintList =
        if (color == null) null
        else ColorStateList.valueOf(color)
}

fun ProgressBar.setTint(color: Int?) {
    indeterminateTintList =
        if (color == null) null
        else ColorStateList.valueOf(color)
    progressTintList =
        if (color == null) null
        else ColorStateList.valueOf(color)
}

fun View.setBackgroundTint(color: Int?) {

    // API 21 doesn't support this

    backgroundTintList =
        if (color == null) null
        else ColorStateList.valueOf(color)
}

fun View.setPadding(left: Int? = null, top: Int? = null, right: Int? = null, bottom: Int? = null) {
    setPadding(left ?: paddingLeft, top ?: paddingTop, right ?: paddingRight, bottom ?: paddingBottom)
}

fun View.setVisible(visible: Boolean, invisible: Int = View.GONE) {
    visibility = if (visible) View.VISIBLE else invisible
}

/**
 * If a view captures clicks at all, then the parent won't ever receive touch events. This is a
 * problem when we're trying to capture link clicks, but tapping or long pressing other areas of
 * the view no longer work. Also problematic when we try to long press on an image in the message
 * view
 */

class CancelableSimpleOnGestureListener(view: View, parentView: View) : SimpleOnGestureListener() {
    private var lastUpEvent: MotionEvent? = null
    private val parent = parentView
    private val thisView = view
    private var textInitiallySelectable = false

    init {
        if (thisView is TextView)
            textInitiallySelectable = thisView.isTextSelectable
    }

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        if (lastUpEvent !== null) {
            parent.onTouchEvent(e)
            parent.onTouchEvent(lastUpEvent)
            lastUpEvent?.recycle()
            lastUpEvent = null
        }
        return true
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        lastUpEvent = MotionEvent.obtain(e)
        thisView.onTouchEvent(e)
        return true
    }

    override fun onDown(e: MotionEvent): Boolean {
        thisView.onTouchEvent(e)
        return true
    }

    override fun onLongPress(e: MotionEvent) {
        parent.onTouchEvent(e)
        // this is kinda odd but we have to 'bounce' the text selectable value so it doesn't
        // start selecting text on a long press, but will start selecting it on the next double-tap
        if (thisView is TextView) {
            thisView.setTextIsSelectable(false)
            thisView.setTextIsSelectable(textInitiallySelectable)
        }
    }
}

fun View.forwardTouches(parent: View): CancelableSimpleOnGestureListener {
    val gestureListener = CancelableSimpleOnGestureListener(this, parent)

    setOnTouchListener(object : OnTouchListener {
        val gestureDetector = GestureDetector(parent.context, gestureListener)

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            return gestureDetector.onTouchEvent(e)
        }
    })

    return gestureListener
}

fun ViewPager.addOnPageChangeListener(listener: (Int) -> Unit) {
    addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
        override fun onPageSelected(position: Int) {
            listener(position)
        }
    })
}

fun RecyclerView.scrapViews() {
    recycledViewPool.clear()
    adapter?.notifyDataSetChanged()
}

/**
 * Keeps the cursor hidden until somebody is actually writing here.
 *
 * Hidden to begin with because a composer can hold focus while its thread is only being read,
 * and a blinking cursor is a panel refresh twice a second on e-ink for nobody.
 *
 * ⚠ Shown on the touch, not the click. The first tap on an unfocused field is spent giving it
 * focus and never reaches a click listener, so a cursor shown "on click" appeared only on the
 * *second* tap -- and a person who tapped once and typed saw none at all. Typing shows it
 * too, for a field that already had focus and was never touched.
 */
@android.annotation.SuppressLint("ClickableViewAccessibility")
fun EditText.showCursorWhenWriting() {
    isCursorVisible = false
    setOnTouchListener { _, event ->
        if (event.actionMasked == MotionEvent.ACTION_DOWN) isCursorVisible = true
        false
    }
    addTextChangedListener(object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) {
            if (hasFocus()) isCursorVisible = true
        }
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    })
}
