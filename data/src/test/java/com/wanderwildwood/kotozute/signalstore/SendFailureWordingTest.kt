package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.whispersystems.signalservice.api.push.exceptions.AuthorizationFailedException
import org.whispersystems.signalservice.api.push.exceptions.DeprecatedVersionException
import org.whispersystems.signalservice.api.push.exceptions.ProofRequiredException
import org.whispersystems.signalservice.api.push.exceptions.RateLimitException
import org.whispersystems.signalservice.api.push.exceptions.ServerRejectedException
import org.whispersystems.signalservice.internal.push.ProofRequiredResponse
import java.util.Optional

/**
 * What a person is told when the service refuses a message.
 *
 * Every catch in the sender used to report `t.message ?: simpleName`, so the four refusals that
 * carry an instruction arrived on screen as the word "ProofRequiredException" -- or, where the
 * exception had no message, as nothing. Each is a different situation with a different thing to
 * do about it, and none of them is "try again", which is what a bare failure invites.
 */
class SendFailureWordingTest {

    private fun proofRequired(retryAfterSeconds: Long): ProofRequiredException {
        // ⚠ Constructed, not mutated. Upstream turned `ProofRequiredResponse` from a Java bean
        // into a Kotlin `data class` with `val`s, so the `apply { token = ... }` this used to do
        // no longer compiles. Caught by this test the moment the service layer was re-vendored,
        // which is the whole reason the copy is in the tree rather than resolved as a jar.
        val response = ProofRequiredResponse(token = "a-token", options = listOf("captcha"))
        return ProofRequiredException(response, retryAfterSeconds)
    }

    @Test
    fun `being asked to prove it is a person says where that can be answered`() {
        val said = SignalSender.explain(proofRequired(0))
        assertTrue(said, said.contains("prove it is a person"))
        // This phone cannot answer the challenge -- no push challenge, no captcha screen -- so
        // the sentence has to name somewhere that can rather than imply this one will.
        assertTrue(said, said.contains("your other phone"))
        assertTrue(said, said.contains("Nothing was sent"))
    }

    @Test
    fun `a retry-after is passed on when the server gave one`() {
        assertTrue(SignalSender.explain(proofRequired(600)).contains("about 10 minutes"))
        // And is silent when it did not. An invented interval is worse than none.
        assertTrue(!SignalSender.explain(proofRequired(0)).contains("Try again"))
    }

    @Test
    fun `rate limiting carries the wait`() {
        val said = SignalSender.explain(RateLimitException(413, "nope", Optional.of(120_000L)))
        assertTrue(said, said.contains("limiting how fast"))
        assertTrue(said, said.contains("about 2 minutes"))
    }

    @Test
    fun `an unlinked device is told so in the same words the socket uses`() {
        assertEquals(
            "This phone is no longer linked to Signal. Link it again to send.",
            SignalSender.explain(AuthorizationFailedException(401, "nope"))
        )
    }

    @Test
    fun `a version the service will not take says the app needs updating`() {
        assertTrue(
            SignalSender.explain(DeprecatedVersionException()).contains("needs updating")
        )
    }

    @Test
    fun `a refusal that will not change says so`() {
        assertTrue(
            SignalSender.explain(ServerRejectedException()).contains("will not help")
        )
    }

    @Test
    fun `anything else keeps whatever it said`() {
        assertEquals("the socket closed", SignalSender.explain(java.io.IOException("the socket closed")))
        // And falls back to the type when there is nothing to quote, as before.
        assertEquals("IOException", SignalSender.explain(java.io.IOException()))
    }

    @Test
    fun `waits are rounded, never counted down to the second`() {
        assertEquals("", SignalSender.afterWards(0))
        assertEquals("", SignalSender.afterWards(-1))
        assertEquals(" Try again in a minute.", SignalSender.afterWards(30))
        assertEquals(" Try again in a minute.", SignalSender.afterWards(89))
        assertEquals(" Try again in about 2 minutes.", SignalSender.afterWards(90))
        assertEquals(" Try again in about 10 minutes.", SignalSender.afterWards(600))
        assertEquals(" Try again in about an hour.", SignalSender.afterWards(3600))
        assertEquals(" Try again in about 2 hours.", SignalSender.afterWards(7200))
        // Rounded up, not down, on purpose: told to come back too early, somebody tries again
        // into the same refusal. Two hours and one second is "about 3 hours" and that is the
        // right direction to be wrong in.
        assertEquals(" Try again in about 3 hours.", SignalSender.afterWards(7201))
    }
}
