package com.wanderwildwood.kotozute.signalstore

import com.wanderwildwood.kotozute.repository.SendFailure
import com.wanderwildwood.kotozute.repository.SendRefused
import org.junit.Assert.assertEquals
import org.junit.Test
import org.whispersystems.signalservice.api.push.exceptions.AuthorizationFailedException
import org.whispersystems.signalservice.api.push.exceptions.DeprecatedVersionException
import org.whispersystems.signalservice.api.push.exceptions.ProofRequiredException
import org.whispersystems.signalservice.api.push.exceptions.RateLimitException
import org.whispersystems.signalservice.api.push.exceptions.ServerRejectedException
import org.whispersystems.signalservice.internal.push.ProofRequiredResponse
import java.util.Optional

/**
 * What the sender decides a refused message means.
 *
 * Every catch in the sender used to report `t.message ?: simpleName`, so the four refusals that
 * carry an instruction arrived on screen as the word "ProofRequiredException" -- or, where the
 * exception had no message, as nothing. Each is a different situation with a different thing to
 * do about it, and none of them is "try again", which is what a bare failure invites.
 *
 * The sender decides which one it was and the screen words it, so this pins the decision. The
 * words, and every sentence this test used to check, are pinned in the presentation module's
 * `SignalWordingTest`, against the English in `strings.xml`.
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
    fun `being asked to prove it is a person is its own kind`() {
        assertEquals(SendFailure.ProofRequired(0), SignalSender.explain(proofRequired(0)))
    }

    @Test
    fun `a retry-after is passed on when the server gave one`() {
        assertEquals(SendFailure.ProofRequired(600), SignalSender.explain(proofRequired(600)))
    }

    @Test
    fun `rate limiting carries the wait, in seconds`() {
        assertEquals(
            SendFailure.RateLimited(120),
            SignalSender.explain(RateLimitException(413, "nope", Optional.of(120_000L)))
        )
        // And nothing, when the server did not say. An invented interval is worse than none.
        assertEquals(
            SendFailure.RateLimited(0),
            SignalSender.explain(RateLimitException(413, "nope", Optional.empty()))
        )
    }

    @Test
    fun `an unlinked device is told so`() {
        assertEquals(SendFailure.Unlinked, SignalSender.explain(AuthorizationFailedException(401, "nope")))
    }

    @Test
    fun `a version the service will not take says the app needs updating`() {
        assertEquals(SendFailure.VersionRefused, SignalSender.explain(DeprecatedVersionException()))
    }

    @Test
    fun `a refusal that will not change says so`() {
        assertEquals(SendFailure.ServerRejected, SignalSender.explain(ServerRejectedException()))
    }

    @Test
    fun `anything else keeps whatever it said`() {
        assertEquals(
            SendFailure.Unexplained("the socket closed"),
            SignalSender.explain(java.io.IOException("the socket closed"))
        )
        // And falls back to the type when there is nothing to quote, as before.
        assertEquals(SendFailure.Unexplained("IOException"), SignalSender.explain(java.io.IOException()))
    }

    @Test
    fun `a refusal the gate already decided is passed through, not quoted`() {
        // The sender's own gate throws these before anything reaches the wire. Quoting their
        // message would put the kind's name in front of somebody instead of a sentence.
        assertEquals(SendFailure.KeysStale, SignalSender.explain(SendRefused(SendFailure.KeysStale)))
        assertEquals(SendFailure.NoCertificate, SignalSender.explain(SendRefused(SendFailure.NoCertificate)))
    }
}
