package com.wanderwildwood.kotozute.util

/**
 * The text an SMS or MMS goes out with: what was typed, then the signature on a line of its
 * own. One function, so that the count beside the message box counts the same characters
 * that are sent.
 */
object Signature {

    fun append(body: String, signature: String): String =
        if (signature.isEmpty()) body else "$body\n$signature"

    /** What the character count is taken of. An empty box counts nothing: there is no
     *  message yet for the signature to be at the end of. */
    fun counted(draft: String, signature: String): String =
        if (draft.isEmpty()) draft else append(draft, signature)
}
