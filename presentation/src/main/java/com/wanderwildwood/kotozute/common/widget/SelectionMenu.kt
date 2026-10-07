package com.wanderwildwood.kotozute.common.widget

import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

/**
 * The menu over a selection, given back what the Kompakt takes out of it.
 *
 * The Kompakt's selection bar is Android's own, laid out the stock way: buttons are placed by
 * width until the bar is full, and the rest go to an overflow that Mudita never draws a ⋮ for.
 * So in a web view everything past Copy and Share is silently gone. In a text field Mudita goes
 * further and leaves only Copy and Paste in the menu at all.
 *
 * This keeps the first [KEEP] items on the bar as Android ordered them and adds a ⋮ that lists
 * the rest in the same order: Select all, Share, then the apps that act on text — Define, Note.
 * Nothing is moved ahead of Android's own items.
 *
 * The same file in every app of this shop that has Android text views or web views (Email has
 * it too, beside its MessageWebView); Compose apps carry TextActions.kt instead.
 */
object SelectionMenu {
    private const val KEEP = 2
    private const val MORE = 0x5e1ec7
    private const val MORE_ORDER = 0xFFFF // the low 16 bits; the high ones are a category

    /**
     * Text apps left out by his choice (2026-10-05): EinkBro's entry is an online dictionary
     * that duplicates Define, and EinkBro has no setting to withdraw it.
     */
    private val NOT_SHOWN = setOf("info.plateaukao.einkbro")

    private class Entry(val title: CharSequence, val run: () -> Unit)

    /** An item of the app's own for the ⋮, such as Messaging's Format. */
    class Own(val title: CharSequence, val run: (start: Int, end: Int) -> Unit)

    /**
     * For a TextView or EditText. Its host needs `<queries>` for PROCESS_TEXT in its manifest.
     * [share] is the app's own word for Share: Android has no public string for it. [own] are the
     * app's own items, listed after Android's and before the apps that act on text.
     */
    @JvmOverloads
    @JvmStatic
    fun fold(view: TextView, share: CharSequence, own: List<Own> = emptyList()) {
        view.customSelectionActionModeCallback = object : ActionMode.Callback {
            var rest = emptyList<Entry>()
            override fun onCreateActionMode(mode: ActionMode, menu: Menu) = true
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                rest = foldMenu(menu) { item -> press(view, menu, mode, item) } + missing(view, menu, mode, share, own)
                showMore(menu, rest.isNotEmpty())
                return true
            }
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId != MORE) return false
                showRest(view, textRect(view), rest)
                return true
            }
            override fun onDestroyActionMode(mode: ActionMode) {}
        }
    }

    /** For a WebView: wrap the callback it starts its selection menu with. */
    fun fold(view: WebView, callback: ActionMode.Callback): ActionMode.Callback = object : ActionMode.Callback2() {
        var rest = emptyList<Entry>()
        override fun onCreateActionMode(mode: ActionMode, menu: Menu) = callback.onCreateActionMode(mode, menu)
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            val result = callback.onPrepareActionMode(mode, menu)
            // The web view's own handler is given the item itself: its text apps all carry one
            // id, so asking the menu to press one by id would press the first of them.
            rest = foldMenu(menu) { item -> callback.onActionItemClicked(mode, item) }
            showMore(menu, rest.isNotEmpty())
            return result
        }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != MORE) return callback.onActionItemClicked(mode, item)
            val rect = Rect()
            if (callback is ActionMode.Callback2) callback.onGetContentRect(mode, view, rect)
            showRest(view, rect, rest)
            return true
        }
        override fun onDestroyActionMode(mode: ActionMode) = callback.onDestroyActionMode(mode)
        override fun onGetContentRect(mode: ActionMode, v: View, outRect: Rect) {
            if (callback is ActionMode.Callback2) callback.onGetContentRect(mode, v, outRect) else super.onGetContentRect(mode, v, outRect)
        }
    }

    /**
     * Presses a folded item of a text view's menu. Android gives every text app in it one id, so
     * pressing by id would always start the first of them (Define in place of Translate); a text
     * app is started here from its own item instead.
     */
    private fun press(view: TextView, menu: Menu, mode: ActionMode, item: MenuItem) {
        val app = item.intent
        if (app?.action != Intent.ACTION_PROCESS_TEXT || app.component == null) {
            menu.performIdentifierAction(item.itemId, 0)
            return
        }
        val start = minOf(view.selectionStart, view.selectionEnd).coerceAtLeast(0)
        val end = maxOf(view.selectionStart, view.selectionEnd).coerceAtLeast(0)
        startTextApp(view, Intent(app), view.text.subSequence(start, end).toString())
        mode.finish()
    }

    /** Hides what will not fit, and returns it in Android's order, each with how to press it. */
    private fun foldMenu(menu: Menu, press: (MenuItem) -> Unit): List<Entry> {
        val items = (0 until menu.size()).map { menu.getItem(it) }.filter { it.itemId != MORE }
        items.filter { it.intent?.component?.packageName in NOT_SHOWN }.forEach { it.isVisible = false }
        val shown = items.filter { it.isVisible && it.isEnabled }.sortedBy { it.order }
        return shown.drop(KEEP).map { item ->
            item.isVisible = false
            Entry(item.title ?: "") { item.isVisible = true; press(item) }
        }
    }

    /**
     * What a Kompakt text field leaves out of its menu, in Android's order: Select all and Share,
     * then the apps that act on text, which are started here so an answer can come back in place
     * of the selection.
     */
    private fun missing(view: TextView, menu: Menu, mode: ActionMode, share: CharSequence, own: List<Own>): List<Entry> {
        val context = view.context
        val out = mutableListOf<Entry>()
        val hasSelection = view.selectionEnd > view.selectionStart
        if (menu.findItem(android.R.id.selectAll) == null && view.text.isNotEmpty() &&
            (view.selectionStart != 0 || view.selectionEnd != view.text.length)
        ) {
            out += Entry(context.getString(android.R.string.selectAll)) { view.onTextContextMenuItem(android.R.id.selectAll) }
        }
        if (!hasSelection) return out
        if (menu.findItem(android.R.id.shareText) == null) {
            out += Entry(share) {
                view.onTextContextMenuItem(android.R.id.shareText)
                mode.finish()
            }
        }
        val start = view.selectionStart
        val end = view.selectionEnd
        for (item in own) out += Entry(item.title) { mode.finish(); item.run(start, end) }
        val query = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        @Suppress("DEPRECATION") // the flags-object overload is Android 13; the Kompakt is 12
        // A stock text field already lists them (only the Kompakt strips them), and those went
        // into the ⋮ through foldMenu; listing them again here would show each twice.
        val listed = (0 until menu.size()).mapNotNull { menu.getItem(it).intent?.component?.className }.toSet()
        val apps = context.packageManager.queryIntentActivities(query, 0)
            .filter { it.activityInfo.exported && it.activityInfo.packageName !in NOT_SHOWN && it.activityInfo.name !in listed }
        for (app in apps) {
            out += Entry(app.loadLabel(context.packageManager)) {
                val text = view.text.subSequence(view.selectionStart, view.selectionEnd).toString()
                startTextApp(view, Intent(query).setClassName(app.activityInfo.packageName, app.activityInfo.name), text)
                mode.finish()
            }
        }
        return out
    }

    private fun showMore(menu: Menu, wanted: Boolean) {
        val more = menu.findItem(MORE)
        when {
            wanted && more == null -> menu.add(Menu.NONE, MORE, MORE_ORDER, "⋮")
            more != null -> more.isVisible = wanted
        }
    }

    /** Where the selection sits inside a text view, in the view's own coordinates. */
    private fun textRect(view: TextView): Rect {
        val layout = view.layout ?: return Rect(0, 0, view.width, view.height)
        val top = layout.getLineTop(layout.getLineForOffset(view.selectionStart))
        val bottom = layout.getLineBottom(layout.getLineForOffset(view.selectionEnd))
        val dy = view.totalPaddingTop - view.scrollY
        return Rect(0, top + dy, view.width, bottom + dy)
    }

    /**
     * The rest as a list. A pop-up that never takes focus, not a dialog: a web view clears its
     * selection the moment focus leaves it, and then the chosen app would be handed nothing.
     */
    private fun showRest(view: View, selection: Rect, rest: List<Entry>) {
        if (rest.isEmpty()) return
        val context = view.context
        val dp = context.resources.displayMetrics.density
        lateinit var popup: PopupWindow
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke((2 * dp).toInt(), Color.BLACK)
                cornerRadius = 12 * dp
            }
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
            for (entry in rest) {
                addView(TextView(context).apply {
                    text = entry.title
                    textSize = 18f
                    setTextColor(Color.BLACK)
                    setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
                    setOnClickListener { popup.dismiss(); entry.run() }
                })
            }
        }
        popup = PopupWindow(list, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = true
            elevation = 0f
        }
        // Under the selection, clear of its drag handles; above it, clear of the bar, when there
        // is no room below. Never over the bar, which stays up while this is open.
        list.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val at = IntArray(2).also { view.getLocationOnScreen(it) }
        val root = view.rootView
        val below = at[1] + selection.bottom + (36 * dp).toInt()
        val y = if (below + list.measuredHeight <= root.height) below
            else (at[1] + selection.top - (72 * dp).toInt() - list.measuredHeight).coerceAtLeast(0)
        val x = (at[0] + selection.centerX() - list.measuredWidth / 2).coerceIn(0, (root.width - list.measuredWidth).coerceAtLeast(0))
        popup.showAtLocation(root, Gravity.TOP or Gravity.START, x, y)
    }

    /**
     * The activity behind a view's context. Views inflated through a ContextThemeWrapper (tayori's
     * compose screen), a dialog or an AppCompat tint wrapper do not hold the activity directly,
     * and a plain cast would quietly make Define's swap do nothing.
     */
    private tailrec fun android.content.Context.findActivity(): ComponentActivity? = when (this) {
        is ComponentActivity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    /**
     * Starts an app that acts on text. From a field that can be written in, it is started for a
     * result, and what comes back replaces the selection, if the selection still holds what was
     * sent. Through the activity's result registry, so the host needs no onActivityResult.
     */
    private fun startTextApp(view: View, intent: Intent, text: String) {
        val activity = view.context.findActivity() ?: return
        val field = view as? TextView
        val editable = field != null && field.onCheckIsTextEditor() && field.isEnabled
        val send = Intent(intent).putExtra(Intent.EXTRA_PROCESS_TEXT, text).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, !editable)
        if (!editable) {
            runCatching { activity.startActivity(send) }
            return
        }
        val start = field!!.selectionStart
        val end = field.selectionEnd
        lateinit var launcher: ActivityResultLauncher<Intent>
        launcher = activity.activityResultRegistry.register("selection-" + System.nanoTime(), ActivityResultContracts.StartActivityForResult()) { result ->
            launcher.unregister()
            val answer = result.data?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT) ?: return@register
            val now = field.text
            if (end <= now.length && now.subSequence(start, end).toString() == text) {
                field.editableText?.replace(start, end, answer)
            }
        }
        runCatching { launcher.launch(send) }.onFailure { launcher.unregister() }
    }
}
