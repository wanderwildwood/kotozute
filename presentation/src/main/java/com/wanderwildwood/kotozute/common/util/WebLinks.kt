package com.wanderwildwood.kotozute.common.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import com.wanderwildwood.kotozute.R

/**
 * Opens a web address in a real browser, never in Android's WebView test shell.
 *
 * Some phones ship `org.chromium.webview_shell`, a developer's test app that answers every web
 * address and then crashes on it. Where it is the only thing that answers, or Android would
 * pick it on its own, the address goes to a real browser instead, chosen by package; with no
 * browser on the phone at all the address is copied and the person is told so. This app never
 * opens a page itself.
 *
 * Any other kind of link (tel:, mailto:, geo:) goes to Android as it is.
 */
object WebLinks {

    /** Test shells, not browsers. */
    private val SHELLS = setOf("org.chromium.webview_shell", "com.android.webview.shell")

    /**
     * Swaps every web-address span in [span] for one that opens through [open], so a tapped link in
     * "Allow" mode cannot reach the WebView shell through the framework's own URLSpan.
     */
    fun guard(span: android.text.SpannableStringBuilder) {
        for (urlSpan in span.getSpans(0, span.length, android.text.style.URLSpan::class.java)) {
            val scheme = Uri.parse(urlSpan.url).scheme?.lowercase()
            if (scheme != "http" && scheme != "https") continue
            val start = span.getSpanStart(urlSpan)
            val end = span.getSpanEnd(urlSpan)
            val flags = span.getSpanFlags(urlSpan)
            span.removeSpan(urlSpan)
            span.setSpan(object : android.text.style.ClickableSpan() {
                override fun onClick(widget: android.view.View) {
                    open(widget.context, Uri.parse(urlSpan.url))
                }
            }, start, end, flags)
        }
    }

    /** True when something was opened. */
    fun open(context: Context, uri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return start(context, intent, uri)
        }

        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        val pm = context.packageManager
        val answering = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val browsers = answering.map { it.activityInfo.packageName }.distinct().filter { it !in SHELLS }
        if (browsers.isEmpty()) {
            nothingToOpen(context, uri)
            return false
        }
        val shells = answering.filter { it.activityInfo.packageName in SHELLS }
        if (shells.isNotEmpty()) {
            if (browsers.size == 1) {
                intent.setPackage(browsers.single())
            } else {
                // Several browsers and a shell: Android's own chooser, with the shell left off.
                val excluded = shells.map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
                return start(
                    context,
                    Intent.createChooser(intent, null)
                        .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, excluded.toTypedArray())
                        .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                    uri
                )
            }
        }
        return start(context, intent, uri)
    }

    private fun start(context: Context, intent: Intent, uri: Uri): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        nothingToOpen(context, uri)
        false
    } catch (e: SecurityException) {
        nothingToOpen(context, uri)
        false
    }

    /** Copies the address, so it can be pasted wherever it can be opened, and says so. */
    private fun nothingToOpen(context: Context, uri: Uri) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val copied = runCatching {
            clipboard?.setPrimaryClip(ClipData.newPlainText(uri.toString(), uri.toString()))
        }.isSuccess && clipboard != null
        val words = if (copied) context.getString(R.string.web_link_no_browser_copied, uri.toString())
        else context.getString(R.string.signal_link_no_app)
        Toast.makeText(context, words, Toast.LENGTH_LONG).show()
    }
}
