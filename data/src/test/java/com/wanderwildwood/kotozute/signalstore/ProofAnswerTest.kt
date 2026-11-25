package com.wanderwildwood.kotozute.signalstore

import com.wanderwildwood.kotozute.repository.SendFailure
import com.wanderwildwood.kotozute.repository.SendFailureCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Answering the proof-of-person challenge, then sending again: upstream's
 * `RecaptchaProofActivity.handleToken` order, with fakes for the server and the database.
 */
class ProofAnswerTest {

    private val unsent = listOf(
        "m1" to SendFailureCodec.encode(SendFailure.ProofRequired(0, "the-challenge")),
        "m2" to SendFailureCodec.encode(SendFailure.ProofNeeded),
        "m3" to SendFailureCodec.encode(SendFailure.SafetyNumberChanged("Ada Whitlock")),
        "m4" to SendFailureCodec.encode(SendFailure.ProofRequired(600)),
        "m5" to ""
    )

    @Test
    fun `the answer goes with the challenge, then every held-up message goes again`() {
        val calls = mutableListOf<String>()
        val taken = ProofAnswer.answer(
            "the-challenge", "the-captcha",
            submit = { c, t -> calls += "submit $c $t"; true },
            unsent = { calls += "unsent"; unsent },
            resend = { calls += "resend $it" }
        )
        assertTrue(taken)
        assertEquals(
            listOf("submit the-challenge the-captcha", "unsent", "resend m1", "resend m2", "resend m4"),
            calls
        )
    }

    @Test
    fun `a refused answer sends nothing`() {
        val resent = mutableListOf<String>()
        val taken = ProofAnswer.answer(
            "the-challenge", "the-captcha",
            submit = { _, _ -> false },
            unsent = { unsent },
            resend = { resent += it }
        )
        assertFalse(taken)
        assertTrue(resent.isEmpty())
    }
}
