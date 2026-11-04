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
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import com.wanderwildwood.kotozute.BuildConfig
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.util.WebLinks
import com.wanderwildwood.kotozute.databinding.AboutDialogBinding

/**
 * What this app is, what it does with what you give it, and where the source is.
 *
 * The same About as every app of this shop, in the same order: the name and version, what it sends,
 * who we are / how to contact us / your rights, its terms, Licenses, the contact address, the
 * source address, the llama; under it one full-width Close button.
 * Licences open inside this same dialog (a list, the app first, then one row per library, each
 * with its own page and full text). A dialog rather than a screen of rows: About is not a setting.
 */
class AboutDialog(private val context: Activity, onVersionLongClick: () -> Unit) : AlertDialog(context, R.style.EInkDialog) {

    private val layout = AboutDialogBinding.inflate(LayoutInflater.from(context))
    private val density = context.resources.displayMetrics.density
    private fun dp(n: Int) = (n * density).toInt()

    /** The one body of the dialog: the About, or the Licences swapped in over it. */
    private val frame = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private val closeButton = QkClose()
    private var licences: LicencesView? = null

    init {
        // One fixed size, the same for the About and the Licences, so swapping them redraws in place.
        frame.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (context.resources.displayMetrics.heightPixels * 0.72f).toInt(),
        )
        setView(frame)
        showAbout()

        layout.name.text = context.getString(R.string.about_name, BuildConfig.VERSION_NAME)
        // Upstream hid the logging switch behind a long press on the About row. The row is
        // gone; the gesture moves to the line that names the version, where it has always
        // lived in other apps.
        layout.name.setOnLongClickListener {
            onVersionLongClick()
            dismiss()
            true
        }
        layout.contact.text = context.getString(R.string.about_write_to, AboutContact.EMAIL)
        layout.contact.setOnClickListener {
            openMail(context.getString(R.string.about_name, BuildConfig.VERSION_NAME))
        }
        layout.source.text = Licences.SOURCE_URL.removePrefix("https://")
        layout.source.setOnClickListener { WebLinks.open(context, Uri.parse(Licences.SOURCE_URL)) }
        // A real browser, never the WebView test shell some phones answer web addresses with;
        // with no browser at all the address is copied and the dialog says so (WebLinks).
        // Straight to the checkout, by the short square.link form the site itself links to,
        // so a regenerated checkout follows it and a published app does not break.
        layout.llama.setOnClickListener {
            WebLinks.open(context, Uri.parse("https://square.link/u/AGu8oT10"))
        }
        layout.licences.setOnClickListener { showLicences() }
        layout.site.setOnClickListener {
            WebLinks.open(context, Uri.parse("https://wanderthe.dev"))
        }
        // System Back: Licences page to list, list to About, About closes.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val open = licences
                when {
                    open == null -> dismiss()
                    !open.back() -> showAbout()
                }
            }
        })
    }

    private fun showAbout() {
        licences = null
        frame.removeAllViews()
        (layout.root.parent as? ViewGroup)?.removeView(layout.root)
        frame.addView(layout.root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        frame.addView(closeButton)
    }

    private fun showLicences() {
        val view = LicencesView(context) { showAbout() }
        licences = view
        frame.removeAllViews()
        frame.addView(view.root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /**
     * The email app, addressed and with the app's name and version as the subject, the way
     * the shared About (shared-about HouseAbout.kt) does it; with no email app, a toast that
     * gives the address.
     */
    private fun openMail(subject: String) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(AboutContact.mailUri(AboutContact.EMAIL, subject)))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.about_no_mail_app, AboutContact.EMAIL), Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(context, context.getString(R.string.about_no_mail_app, AboutContact.EMAIL), Toast.LENGTH_LONG).show()
        }
    }

    /** The one button under the About: Close, an outline the width of the dialog. */
    private fun QkClose() = com.wanderwildwood.kotozute.common.widget.QkTextView(context).apply {
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
}
