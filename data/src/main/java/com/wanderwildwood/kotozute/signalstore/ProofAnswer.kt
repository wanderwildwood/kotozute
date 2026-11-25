package com.wanderwildwood.kotozute.signalstore

import com.wanderwildwood.kotozute.repository.SendFailure
import com.wanderwildwood.kotozute.repository.SendFailureCodec

/**
 * Answering Signal's proof-of-person challenge on this phone, then sending again what it held up.
 *
 * Upstream's `RecaptchaProofActivity.handleToken`: submit the solved captcha with the challenge
 * token, and only when the server takes it, `RateLimitUtil.retryAllRateLimitedMessages` -- every
 * message the challenge stopped, not just the one tapped. A refused or failed answer sends
 * nothing, and the messages stay not sent with the tap still on them.
 *
 * The steps are passed in so the order can be tested without a server or a database.
 */
internal object ProofAnswer {

    /** Whether a stored reason is the challenge, so a solved one should send that message again. */
    fun heldUp(stored: String): Boolean = when (SendFailureCodec.decode(stored)) {
        is SendFailure.ProofRequired, SendFailure.ProofNeeded -> true
        else -> false
    }

    /**
     * @param unsent our own not-sent messages, as id and stored reason.
     * @return whether the server took the answer.
     */
    fun answer(
        challenge: String,
        captcha: String,
        submit: (challenge: String, captcha: String) -> Boolean,
        unsent: () -> List<Pair<String, String>>,
        resend: (id: String) -> Unit
    ): Boolean {
        if (!submit(challenge, captcha)) return false
        unsent().filter { (_, stored) -> heldUp(stored) }.forEach { (id, _) -> resend(id) }
        return true
    }
}
