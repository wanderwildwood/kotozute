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
package com.wanderwildwood.kotozute.common.widget

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.google.android.mms.ContentType
import com.wanderwildwood.kotozute.common.util.ClipboardUtils
import com.wanderwildwood.kotozute.common.util.TextViewStyler
import com.wanderwildwood.kotozute.injection.appComponent
import com.wanderwildwood.kotozute.util.tryOrNull
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject


/**
 * Custom implementation of EditText to allow for dynamic text colors
 *
 * Beware of updating to extend AppCompatTextView, as this inexplicably breaks the view in
 * the contacts chip view
 */
class QkEditText @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null)
    // A steady caret rather than the platform's blinking one: see StaticCaretEditText.
    : StaticCaretEditText(context, attrs) {

    @Inject lateinit var textViewStyler: TextViewStyler

    val backspaces: Subject<Unit> = PublishSubject.create()
    val inputContentSelected: Subject<InputContentInfoCompat> = PublishSubject.create()
    var supportsInputContent: Boolean = false

    /**
     * Takes a pasted picture or file, where the screen this box is on can attach one. Given
     * the content addresses on the clipboard; true means they were taken.
     *
     * Left null, or answering false, Paste does what it always did. So does a clipboard of
     * words: this is only asked when there is nothing on it to type.
     */
    var onPasteContent: ((List<Uri>) -> Boolean)? = null

    init {
        if (!isInEditMode) {
            appComponent.inject(this)
            textViewStyler.applyAttributes(this, attrs)
        } else {
            TextViewStyler.applyEditModeAttributes(this, attrs)
        }
    }

    /**
     * Keeps the line being typed in view once the box is full (six lines) and scrolls inside.
     *
     * The message boxes hide the cursor (`cursorVisible="false"`) so the panel is not redrawn
     * for a blink. With no visible cursor Android does not scroll the box to follow the typing,
     * so a long message ran on below the box's edge where it could not be seen (forum report).
     * Posted, so the layout has taken the new text before the box scrolls to it.
     */
    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (selEnd < 0) return
        post {
            val end = selectionEnd
            if (end >= 0 && layout != null) bringPointIntoView(end)
        }
    }

    /**
     * Paste, when what was copied is a picture. Android would otherwise type the picture's
     * address into the box as text.
     */
    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
            val take = onPasteContent
            if (take != null) {
                val uris = ClipboardUtils.pastedContent(context)
                if (uris.isNotEmpty() && take(uris)) return true
            }
        }
        return super.onTextContextMenuItem(id)
    }

    /**
     * Paste, where a press and hold selected nothing.
     *
     * Android offers Paste over an empty box, or over the gap after the words, from the menu
     * that belongs to its own caret. That caret is switched off here for the steady one (see
     * StaticCaretEditText), and the menu goes with it: an empty box had no Paste at all. So
     * when a hold leaves no selection and there is something to paste, the same floating bar
     * is raised with the one item.
     */
    override fun performLongClick(x: Float, y: Float): Boolean {
        val handled = super.performLongClick(x, y)
        if (!isEnabled || !isFocusable || selectionEnd > selectionStart || !hasSomethingToPaste()) return handled
        if (!isFocused) requestFocus()
        startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(Menu.NONE, android.R.id.paste, 0, android.R.string.paste)
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId == android.R.id.paste) onTextContextMenuItem(android.R.id.paste)
                mode.finish()
                return true
            }
            override fun onDestroyActionMode(mode: ActionMode) = Unit
            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                // Where the hold was, so the bar sits by the finger and not over the box.
                outRect.set(x.toInt(), y.toInt(), x.toInt(), y.toInt())
            }
        }, ActionMode.TYPE_FLOATING)
        return true
    }

    private fun hasSomethingToPaste(): Boolean = tryOrNull {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).hasPrimaryClip()
    } ?: false

    override fun onCreateInputConnection(editorInfo: EditorInfo): InputConnection {

        val inputConnection = object : InputConnectionWrapper(super.onCreateInputConnection(editorInfo), true) {
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DEL) {
                    backspaces.onNext(Unit)
                }
                return super.sendKeyEvent(event)
            }


            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength == 1 && afterLength == 0) {
                    backspaces.onNext(Unit)
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }
        }

        if (supportsInputContent) {
            EditorInfoCompat.setContentMimeTypes(editorInfo, arrayOf(
                    ContentType.IMAGE_JPEG,
                    ContentType.IMAGE_JPG,
                    ContentType.IMAGE_PNG,
                    ContentType.IMAGE_GIF))
        }

        val callback = InputConnectionCompat.OnCommitContentListener { inputContentInfo, flags, opts ->
            val grantReadPermission = flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 && grantReadPermission) {
                return@OnCommitContentListener tryOrNull {
                    inputContentInfo.requestPermission()
                    inputContentSelected.onNext(inputContentInfo)
                    true
                } ?: false

            }

            true
        }

        return InputConnectionCompat.createWrapper(inputConnection, editorInfo, callback)
    }

}