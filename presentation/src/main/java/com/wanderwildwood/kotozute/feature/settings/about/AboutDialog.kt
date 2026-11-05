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
package com.wanderwildwood.kotozute.feature.settings.about

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.wanderwildwood.kotozute.BuildConfig
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.util.TextViewStyler
import com.wanderwildwood.kotozute.common.util.WebLinks
import com.wanderwildwood.kotozute.common.widget.QkTextView
import com.wanderwildwood.kotozute.databinding.AboutDialogBinding

/**
 * What this app is, what it does with what you give it, and where the source is: the same About
 * as every app of this shop (STYLE.md, "About"; shared-about), drawn in Views.
 *
 * The name and version head it. Under them, in one paged column: the short description, the
 * source address, the llama, then the drawers (Privacy, Who we are, How to contact us, Your
 * rights, Terms, Licenses), all closed whenever the dialog opens, several open at once if wanted.
 * A drawer is its heading in bold, no mark, at least 48 dp, pressed anywhere, no ripple. The
 * sentence in How to contact us that gives the address is the row that writes. Licenses opens to
 * the app's license line and one small drawer per library, its license text inline. Every line
 * of an open drawer is its own row, so pages still turn on whole rows. The rows come from
 * AboutModel.kt, which mirrors shared-about's logic. Under the list one full-width Close button.
 */
class AboutDialog(private val context: Activity, onVersionLongClick: () -> Unit) : AlertDialog(context, R.style.EInkDialog) {

    private val layout = AboutDialogBinding.inflate(LayoutInflater.from(context))
    private val density = context.resources.displayMetrics.density
    private fun dp(n: Int) = (n * density).toInt()

    private val title = context.getString(R.string.about_name, BuildConfig.VERSION_NAME)

    private val data = messagingAboutData(title) { context.getString(it) }
    private val rows = aboutRows(data)

    /** The build's list of what the APK holds (res/raw/aboutlibraries.json), read once. */
    private val licenceRows: List<Licences.Row> by lazy {
        val components = runCatching {
            licenceComponentsFromJson(
                context.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() }
            )
        }.getOrDefault(emptyList())
        Licences.rows(context.getString(R.string.about_name_plain), components)
    }

    /** The drawers open now; none when the dialog opens. */
    private var open = emptySet<String>()

    private val styler: TextViewStyler = QkTextView(context).textViewStyler

    init {
        // One fixed size, so opening and closing drawers redraws in place.
        val frame = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        frame.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (context.resources.displayMetrics.heightPixels * 0.72f).toInt(),
        )
        frame.addView(layout.root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        frame.addView(closeButton())
        setView(frame)

        layout.name.text = title
        // Upstream hid the logging switch behind a long press on the About row. The row is
        // gone; the gesture moves to the line that names the version, where it has always
        // lived in other apps.
        layout.name.setOnLongClickListener {
            onVersionLongClick()
            dismiss()
            true
        }
        render()
    }

    /** Every drawer closed and the first page, each time the dialog opens. */
    override fun show() {
        open = emptySet()
        render()
        layout.scroll.scrollTo(0, 0)
        super.show()
    }

    private fun toggle(key: String) {
        val opening = key !in open
        open = if (opening) open + key else open - key
        render()
        if (opening) layout.scroll.post { keepOnPage(key) }
    }

    /**
     * A drawer opened at the foot of a page whose first line does not fit there comes to the top,
     * so the press is answered on the page it was made on. Otherwise the page stays.
     */
    private fun keepOnPage(key: String) {
        val list = layout.rows
        val i = (0 until list.childCount).firstOrNull { list.getChildAt(it).tag == key } ?: return
        val head = list.getChildAt(i)
        val next = list.getChildAt(i + 1)
        val top = layout.scroll.scrollY
        val foot = top + layout.scroll.height - layout.scroll.paddingTop
        val headTop = list.top + head.top
        if (headTop > top && (next == null || list.top + next.bottom > foot)) {
            layout.scroll.scrollTo(0, headTop)
        }
    }

    private fun render() {
        val items = aboutItems(rows, open, context.getString(R.string.about_licences)) {
            aboutLicenceItems(
                appLine = context.getString(R.string.licences_licence_words),
                rows = licenceRows,
                open = open,
                readRaw = { raw -> context.resources.openRawResource(raw).bufferedReader().use { it.readText() } },
                noText = { context.getString(R.string.licences_no_text, it) },
            )
        }
        layout.rows.removeAllViews()
        items.forEach { item ->
            val view = when (item) {
                is AboutItem.Head -> head(item)
                is AboutItem.Text -> text(item.text, item.depth, item.bold).apply { setPadding(paddingLeft, dp(4), 0, dp(4)) }
                is AboutItem.Link -> line(item.label, item.depth) { WebLinks.open(context, Uri.parse(item.url)) }
                is AboutItem.Contact -> line(item.label ?: context.getString(R.string.about_write_to, item.address), item.depth) {
                    openMail(item.address)
                }
                AboutItem.Llama -> llama()
            }
            view.tag = item.key
            layout.rows.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** Reading text: the settings' secondary size, 16 sp at the smallest. */
    private fun text(value: String, depth: Int = 0, bold: Boolean = false, size: Int = TextViewStyler.SIZE_SECONDARY) =
        QkTextView(context).apply {
            text = value
            styler.setTextSize(this, size)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(INDENT_DP) * depth, 0, 0, 0)
        }

    /**
     * A drawer's row: the heading (and a second line, for a library) and no mark, as tall as a
     * thumb, pressed anywhere to open or close. No ripple: on e-ink the press is the text appearing.
     */
    private fun head(item: AboutItem.Head): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(INDENT_DP) * item.depth, dp(if (item.depth == 0) 2 else 6), 0, 0)
            contentDescription = context.getString(
                if (item.open) R.string.about_close_drawer else R.string.about_open_drawer, item.heading,
            )
            setOnClickListener { toggle(item.key) }
            asButton(this)
        }
        box.addView(text(item.heading, bold = item.depth == 0).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        item.subtitle?.let { box.addView(text(it).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }) }
        return box
    }

    /** A line that opens something: as tall as a thumb, in words. */
    private fun line(label: String, depth: Int, onClick: () -> Unit) = text(label, depth).apply {
        minHeight = dp(48)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(paddingLeft, dp(6), 0, 0)
        setBackgroundResource(selectable())
        setOnClickListener { onClick() }
    }

    /**
     * The site's address, and the llama with "Feed the llamas", which goes straight to the
     * checkout by the short square.link form the site itself links to, so a regenerated checkout
     * follows it and a published app does not break. A real browser, never the WebView test shell
     * some phones answer web addresses with (WebLinks).
     */
    private fun llama(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        row.addView(text(aboutAddress(ABOUT_WEBSITE_URL), size = TextViewStyler.SIZE_TERTIARY).apply {
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(6), 0)
            setBackgroundResource(selectable())
            setOnClickListener { WebLinks.open(context, Uri.parse(ABOUT_WEBSITE_URL)) }
        })
        val llama = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(6), 0, dp(6), 0)
            setBackgroundResource(selectable())
            setOnClickListener { WebLinks.open(context, Uri.parse(ABOUT_DONATE_URL)) }
        }
        llama.addView(ImageView(context).apply {
            setImageResource(R.drawable.llama)
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(22), dp(22)))
        llama.addView(text(context.getString(R.string.about_llama), size = TextViewStyler.SIZE_TERTIARY).apply {
            setPadding(dp(6), 0, 0, 0)
        })
        row.addView(llama)
        return row
    }

    private fun selectable(): Int {
        val value = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return value.resourceId
    }

    /** A screen reader hears a drawer's row as a button: "Open Privacy". */
    private fun asButton(view: View) {
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        }
    }

    /**
     * The email app, addressed and with the app's name and version as the subject, the way
     * the shared About does it; with no email app, a toast that gives the address.
     */
    private fun openMail(address: String) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(AboutContact.mailUri(address, title)))
            .putExtra(Intent.EXTRA_SUBJECT, title)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            noMail(context, address)
        } catch (_: SecurityException) {
            noMail(context, address)
        }
    }

    private fun noMail(context: Context, address: String) =
        Toast.makeText(context, context.getString(R.string.about_no_mail_app, address), Toast.LENGTH_LONG).show()

    /** The one button under the About: Close, an outline the width of the dialog. */
    private fun closeButton() = QkTextView(context).apply {
        text = context.getString(R.string.button_close)
        gravity = Gravity.CENTER
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.BLACK)
        setBackgroundResource(R.drawable.setup_option)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(24), dp(10), dp(24), dp(14)) }
        setOnClickListener { dismiss() }
    }

    private companion object {
        /** How far a drawer inside Licenses sits in. */
        const val INDENT_DP = 14
    }
}

/**
 * Messaging's own words for the About, in STYLE.md's order: the short description, then Privacy
 * (where things go, permissions, what it does not do), Who we are, How to contact us, Your
 * rights, Terms with the forks it continues. [string] reads a string resource.
 */
internal fun messagingAboutData(title: String, string: (Int) -> String) = AboutData(
    title = title,
    sections = listOf(
        AboutSection(null, string(R.string.about_description)),
        AboutSection(string(R.string.about_privacy_heading), string(R.string.about_privacy_where)),
        AboutSection(null, string(R.string.about_privacy_permissions)),
        AboutSection(null, string(R.string.about_privacy_close)),
        AboutSection(string(R.string.privacy_who_title), string(R.string.privacy_who)),
        AboutSection(string(R.string.privacy_contact_title), string(R.string.privacy_contact)),
        AboutSection(string(R.string.privacy_rights_title), string(R.string.privacy_rights)),
        AboutSection(string(R.string.about_terms_heading), string(R.string.about_terms)),
        AboutSection(null, string(R.string.about_fork)),
    ),
    sourceUrl = Licences.SOURCE_URL,
)
