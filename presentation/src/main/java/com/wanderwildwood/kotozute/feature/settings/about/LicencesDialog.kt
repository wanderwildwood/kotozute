package com.wanderwildwood.kotozute.feature.settings.about

import android.app.Activity
import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.util.WebLinks
import com.wanderwildwood.kotozute.common.widget.PagingScrollView
import com.wanderwildwood.kotozute.common.widget.QkTextView

/**
 * Where the source is, what this app is licensed under, what it is built with, and the full
 * text of each licence. Paged by paragraph, like the About dialog it opens from.
 */
class LicencesDialog(private val activity: Activity) : AlertDialog(activity, R.style.EInkDialog) {

    init {
        val inflater = LayoutInflater.from(activity)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad - 4, pad, 8)
        }

        fun line(text: CharSequence, bold: Boolean = false, onClick: (() -> Unit)? = null) {
            val v = inflater.inflate(R.layout.licence_line, column, false) as QkTextView
            v.text = text
            if (bold) v.setTypeface(v.typeface, android.graphics.Typeface.BOLD)
            if (onClick != null) {
                v.minHeight = (48 * v.resources.displayMetrics.density).toInt()
                v.gravity = android.view.Gravity.CENTER_VERTICAL
                v.setOnClickListener { onClick() }
            }
            column.addView(v)
        }

        fun text(res: Int) = activity.resources.openRawResource(res).bufferedReader().use { it.readText() }

        line(activity.getString(R.string.licences_source), bold = true)
        line(Licences.SOURCE_URL) { WebLinks.open(activity, Licences.sourceUri()) }
        line(activity.getString(R.string.licences_licence), bold = true)
        line(activity.getString(R.string.licences_licence_words))
        line(activity.getString(R.string.licences_built_with), bold = true)
        Licences.parts.forEach { part ->
            line(Licences.line(part)) { WebLinks.open(activity, android.net.Uri.parse(part.url)) }
        }

        listOf(
            R.string.licences_agpl to R.raw.licence_agpl3,
            R.string.licences_gpl to R.raw.licence_gpl3,
            R.string.licences_apache to R.raw.licence_apache2,
            R.string.licences_mit to R.raw.licence_mit,
            R.string.licences_bsd to R.raw.licence_bsd,
            R.string.licences_ofl to R.raw.licence_ofl
        ).forEach { (title, raw) ->
            line(activity.getString(title), bold = true)
            Licences.paragraphs(text(raw)).forEach { line(it) }
        }

        val scroll = PagingScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setView(scroll)
        setButton(DialogInterface.BUTTON_POSITIVE, activity.getString(R.string.button_close)) { _, _ -> }
    }
}
