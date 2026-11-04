package com.wanderwildwood.kotozute.feature.settings.about

/** The About's contact row: the one address every app gives (shared-about, ABOUT_CONTACT_EMAIL). */
object AboutContact {
    const val EMAIL = "email@wanderthe.dev"

    /** mailto: with the subject encoded, spaces as %20 (as shared-about's aboutMailUri). */
    fun mailUri(address: String, subject: String): String =
        "mailto:$address?subject=" + java.net.URLEncoder.encode(subject, "UTF-8").replace("+", "%20")
}
