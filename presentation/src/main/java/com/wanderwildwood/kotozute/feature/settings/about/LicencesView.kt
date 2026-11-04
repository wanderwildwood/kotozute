package com.wanderwildwood.kotozute.feature.settings.about

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.util.WebLinks
import com.wanderwildwood.kotozute.common.widget.PagingScrollView
import com.wanderwildwood.kotozute.common.widget.QkTextView

/**
 * The Licences screen, the way Mudita's own apps do it: a plain list whose first row is the app
 * itself, then one row per library (its name, its author under it), alphabetical. A row opens that
 * project's page: author, licence, a link to its website and the full licence text, paged by whole
 * paragraphs. The app's page carries the AGPL text and the Source code link. It lives inside the
 * About dialog (same window); [close] gives the About back. System Back goes page to list, then
 * list to About ([back]).
 */
class LicencesView(private val activity: Activity, private val close: () -> Unit) {

    private val density = activity.resources.displayMetrics.density
    private fun dp(n: Int) = (n * density).toInt()

    /** The one view the host shows: a list or a page, swapped in place. */
    val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    private var onPage = false

    init {
        showList()
    }

    /** True when this handled the Back press (it went from a page to the list). */
    fun back(): Boolean {
        if (!onPage) return false
        showList()
        return true
    }

    private fun showList() {
        onPage = false
        val column = column()
        Licences.rows(activity.getString(R.string.about_name_plain)).forEach { row ->
            column.addView(listRow(row))
        }
        show(column, activity.getString(R.string.licences_back)) { close() }
    }

    private fun showPage(row: Licences.Row) {
        onPage = true
        val column = column()
        fun text(value: CharSequence, bold: Boolean = false, top: Int = 8, onClick: (() -> Unit)? = null) {
            val v = QkTextView(activity)
            v.setPadding(0, dp(top), 0, dp(top / 2))
            v.text = value
            v.textSize = 16f
            if (bold) v.setTypeface(v.typeface, Typeface.BOLD)
            if (onClick != null) {
                v.minHeight = dp(48)
                v.gravity = Gravity.CENTER_VERTICAL
                v.paintFlags = v.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                v.setOnClickListener { onClick() }
            }
            column.addView(v, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        text(row.title, bold = true, top = 0)
        if (row.author.isNotEmpty()) text(row.author)
        text(row.licence)
        text(
            if (row.isApp) activity.getString(R.string.licences_source) else row.url.removePrefix("https://"),
            onClick = { WebLinks.open(activity, Uri.parse(row.url)) },
        )
        if (row.isApp) text(activity.getString(R.string.licences_licence_words))
        val raw = row.raw
        if (raw == null) {
            text(activity.getString(R.string.licences_no_text, row.licence))
        } else {
            val full = activity.resources.openRawResource(raw).bufferedReader().use { it.readText() }
            Licences.paragraphs(full).forEach { text(it) }
        }
        show(column, activity.getString(R.string.licences_back)) { showList() }
    }

    private fun column() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(20), dp(24), dp(8))
    }

    /** A list row: the name, with the author (or the licence, for the app) under it; 48 dp or more. */
    private fun listRow(row: Licences.Row): View {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { showPage(row) }
        }
        val name = QkTextView(activity).apply { text = row.title; textSize = 16f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        val sub = QkTextView(activity).apply {
            text = if (row.isApp) row.licence else row.author
            textSize = 16f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
        box.addView(name)
        box.addView(sub)
        return box
    }

    /** Puts [column] in a paged scroller with the Back button under it, as the whole of the view. */
    private fun show(column: View, backLabel: String, onBack: () -> Unit) {
        root.removeAllViews()
        val scroll = PagingScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(outlineButton(backLabel, onBack))
    }

    /** An outlined button the width of the dialog, as MMD draws a secondary button. */
    fun outlineButton(label: String, onClick: () -> Unit) = QkTextView(activity).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.BLACK)
        setBackgroundResource(R.drawable.setup_option)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(24), dp(10), dp(24), dp(14)) }
        setOnClickListener { onClick() }
    }
}
