package com.wanderwildwood.kotozute.common.util

import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.View
import android.widget.TextView
import com.wanderwildwood.kotozute.util.Preferences
import io.reactivex.subjects.Subject

/**
 * Makes the links in a message body clickable, the same way on both rails.
 *
 * The SMS thread grew its own copy of this and the Signal thread had none, so a link in a
 * Signal message was text you had to retype. One place, so the two cannot drift and so
 * "Ask before opening" means the same thing wherever a link appears.
 */
object MessageLinks {

    /**
     * Returns [body] with its links spanned according to the preference, and points the view's
     * movement method at them.
     *
     * - **Block**: nothing is linked. The text is left exactly as it arrived.
     * - **Ask**: links are spanned to [askIntent] instead of opening, so the confirm dialog
     *   is what decides. A message from a stranger is the ordinary case for SMS, and one tap
     *   should not be able to open anything.
     * - **Allow**: ordinary links, opened by the system.
     */
    fun apply(
        view: TextView,
        body: CharSequence,
        prefs: Preferences,
        askIntent: Subject<Uri>
    ): CharSequence {
        val mode = prefs.messageLinkHandling.get()
        if (mode == Preferences.MESSAGE_LINK_HANDLING_BLOCK) {
            view.movementMethod = null
            return body
        }

        val span = SpannableStringBuilder(body)
        Linkify.addLinks(
            span,
            Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES or Linkify.PHONE_NUMBERS
        )

        if (mode == Preferences.MESSAGE_LINK_HANDLING_ASK) {
            // Replace each URLSpan with one that asks first. Collected before the loop
            // mutates the builder: getSpans over a builder being edited is a way to miss one.
            val urls = span.getSpans(0, span.length, URLSpan::class.java)
            for (urlSpan in urls) {
                val start = span.getSpanStart(urlSpan)
                val end = span.getSpanEnd(urlSpan)
                val flags = span.getSpanFlags(urlSpan)
                span.removeSpan(urlSpan)
                span.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) = askIntent.onNext(Uri.parse(urlSpan.url))
                }, start, end, flags)
            }
        }

        // linksClickable false in Ask mode so the framework does not open a URLSpan behind the
        // dialog's back; the ClickableSpans above still fire.
        view.linksClickable = mode != Preferences.MESSAGE_LINK_HANDLING_ASK
        view.movementMethod = LongClickLinkMovementMethod.getInstance()
        return span
    }

    /** Whether [body] contains anything that would be linked. Cheap enough to call per bind. */
    fun hasLinks(body: CharSequence): Boolean {
        if (body.isEmpty()) return false
        val probe = SpannableStringBuilder(body)
        Linkify.addLinks(
            probe,
            Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES or Linkify.PHONE_NUMBERS
        )
        return probe.getSpans(0, probe.length, URLSpan::class.java).isNotEmpty()
    }
}

