package com.wanderwildwood.kotozute.repository

/**
 * Why Signal's server refused this device, as a kind rather than a sentence.
 *
 * Stored as it is (`Preferences.signalRejected`) and worded on screen, so it reads in the
 * phone's language and a stored reason follows a later translation. Builds before this stored
 * the English sentence itself; [of] still recognises those.
 */
object SignalRefusal {
    /** The account no longer has this device: it has to be linked again. */
    const val UNLINKED = "unlinked"

    /**
     * The same refusal, on a phone registered as the number's own Signal phone rather than
     * linked: it is registered again, not linked again (t/15210).
     */
    const val UNREGISTERED = "unregistered"

    /** [UNLINKED] as it applies to this phone: [UNREGISTERED] when it is the account's primary. */
    fun forDevice(kind: String, primary: Boolean): String =
        if (kind == UNLINKED && primary) UNREGISTERED else kind

    /** The server will not talk to this version of the app. */
    const val DEPRECATED = "deprecated"

    /** [stored] as one of the kinds above, or null for anything else. */
    fun of(stored: String): String? = when {
        stored == UNLINKED || stored.startsWith("This phone is no longer linked to Signal") -> UNLINKED
        stored == UNREGISTERED -> UNREGISTERED
        stored == DEPRECATED || stored.startsWith("Signal will not accept this version") -> DEPRECATED
        else -> null
    }
}
