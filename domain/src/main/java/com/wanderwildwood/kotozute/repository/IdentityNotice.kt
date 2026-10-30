package com.wanderwildwood.kotozute.repository

/**
 * The pure rules behind the "safety number has changed" notice in a conversation.
 *
 * The rule is Signal's: a send that meets a changed identity key is not sent, and the person
 * decides, in front of the thing they were sending, whether to check the number or accept the
 * new key. Nothing here touches a screen or a store, so each rule can be tested on its own.
 */
object IdentityNotice {

    /**
     * Whether a failure is the recipient's identity key being untrusted: the typed refusals
     * this app throws, or libsignal's own `UntrustedIdentityException` anywhere in the causes
     * (matched by name so this module needs no libsignal).
     */
    fun isIdentityProblem(t: Throwable): Boolean =
        generateSequence(t) { it.cause }.take(8).any {
            it is SafetyNumberChanged ||
                (it is SendRefused && it.failure is SendFailure.SafetyNumberChanged) ||
                it.javaClass.simpleName == "UntrustedIdentityException"
        }

    /**
     * Whether the notice is on screen. Only for a one-to-one conversation (a group has one key
     * per member and no single thing to accept), only while a message of ours is waiting, and
     * only while the key is still untrusted or the last send was refused for it.
     */
    fun shows(
        direct: Boolean,
        keyChanged: Boolean,
        lastSendRefusedForIdentity: Boolean,
        waitingMessages: Int
    ): Boolean = direct && waitingMessages > 0 && (keyChanged || lastSendRefusedForIdentity)

    /** Ids of the waiting messages, oldest first: the order they were written in. */
    fun retryOrder(waiting: List<Pair<String, Long>>): List<String> =
        waiting.sortedBy { it.second }.map { it.first }

    /**
     * What happened when the person pressed "Accept and send".
     *
     * @property accepted the new key was marked trusted.
     * @property sent how many waiting messages went.
     * @property stillWaiting how many are still queued (never dropped).
     */
    data class Outcome(val accepted: Boolean, val sent: Int, val stillWaiting: Int)

    /**
     * Accepts the key and, only if that worked, tries each waiting message once, oldest first.
     * Nothing is sent before the accept. The first message that fails stops the run, so the
     * rest stay queued in order and none is sent out of turn.
     */
    fun acceptAndRetry(
        waiting: List<String>,
        accept: () -> Boolean,
        retry: (String) -> Unit
    ): Outcome {
        val ok = runCatching(accept).getOrDefault(false)
        if (!ok) return Outcome(accepted = false, sent = 0, stillWaiting = waiting.size)
        var sent = 0
        for (id in waiting) {
            if (runCatching { retry(id) }.isFailure) break
            sent++
        }
        return Outcome(accepted = true, sent = sent, stillWaiting = waiting.size - sent)
    }
}
