package com.wanderwildwood.kotozute.repository

/**
 * A send was refused because the recipient's safety number changed.
 *
 * ⚠ A type rather than a sentence, so the screen can **offer the decision** instead of
 * reprinting the problem. Signal never leaves somebody with only a message here: a send blocked
 * this way puts a sheet in front of them offering "Send anyway" — which trusts the new key and
 * resends in one action — alongside "Verify safety number". The thing they were already trying
 * to do is what carries the decision.
 *
 * This app used to fail the send with a string naming a raw service id, and leave the person to
 * work out unaided that a row on another screen was the remedy.
 *
 * @param threadKey the conversation it happened in, so the screen can act on it.
 * @param name what to call them, where the app knows. Null means it has only an id, and the
 *   screen should say "them" rather than show hexadecimal.
 */
class SafetyNumberChanged(
    val threadKey: String,
    val name: String?
) : Exception("the safety number changed for ${name ?: "them"}")
