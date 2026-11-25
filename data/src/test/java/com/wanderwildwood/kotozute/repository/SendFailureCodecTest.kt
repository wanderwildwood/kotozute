package com.wanderwildwood.kotozute.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reason a message was not sent is stored on it and read back when the row is drawn,
 * possibly by a later build. Every kind has to come back as itself.
 */
class SendFailureCodecTest {

    /**
     * One of every kind -- 30 when this was written -- and the named ones both with and without a name.
     * The `when` in [SendFailureCodec.encode] is exhaustive, so a new kind will not compile
     * until it is encoded; add it here too, or its decode goes untested.
     */
    private val every: List<SendFailure> = listOf(
        SendFailure.ProofRequired(30),
        SendFailure.RateLimited(3600),
        SendFailure.Unlinked,
        SendFailure.Unregistered,
        SendFailure.VersionRefused,
        SendFailure.ServerRejected,
        SendFailure.TheyLeft,
        SendFailure.Unexplained("the socket closed"),
        SendFailure.SafetyNumberChanged("Ada Whitlock"),
        SendFailure.SafetyNumberChanged(null),
        SendFailure.NotOnSignal("Tomas Reyes"),
        SendFailure.NotOnSignal(null),
        SendFailure.Unreachable,
        SendFailure.KeyUnusable("Ada Whitlock"),
        SendFailure.KeyUnusable(null),
        SendFailure.TooFast(90_000),
        SendFailure.TooFast(null),
        SendFailure.ProofNeeded,
        SendFailure.ServerSilent,
        SendFailure.NoLongerHeld,
        SendFailure.NoReachableMembers,
        SendFailure.NobodyReached(4),
        SendFailure.PrimaryRefusedRequest,
        SendFailure.TooLong,
        SendFailure.AttachmentUnprepared("it is no longer on this phone"),
        SendFailure.KeysStale,
        SendFailure.NoCertificate,
        SendFailure.NotInGroup,
        SendFailure.GroupEnded,
        SendFailure.GroupUnreachable,
        SendFailure.AdminsOnly,
        SendFailure.NumberOnly,
        SendFailure.NoGroupKey,
        SendFailure.NotSetUp,
        SendFailure.SignalOff,
    )

    @Test
    fun `every kind comes back as itself`() {
        every.forEach { assertEquals(it, SendFailureCodec.decode(SendFailureCodec.encode(it))) }
    }

    @Test
    fun `text with a newline in it survives`() {
        val f = SendFailure.Unexplained("line one\nline two")
        assertEquals(f, SendFailureCodec.decode(SendFailureCodec.encode(f)))
    }

    @Test
    fun `nothing stored, or a kind this build does not know, is no reason`() {
        assertNull(SendFailureCodec.decode(""))
        assertNull(SendFailureCodec.decode("SomethingNewer"))
        assertNull(SendFailureCodec.decode("NobodyReached\nmany"))
    }

    @Test
    fun `a refusal or a kept message gives its own reason, anything else its text`() {
        assertEquals(SendFailure.NotSetUp, SendFailureCodec.of(SendRefused(SendFailure.NotSetUp)))
        assertEquals(
            SendFailure.SignalOff,
            SendFailureCodec.of(NotSent(SendFailure.SignalOff, IllegalStateException("x")))
        )
        assertEquals(
            SendFailure.SafetyNumberChanged("Ada Whitlock"),
            SendFailureCodec.of(SafetyNumberChanged("direct:x", "Ada Whitlock"))
        )
        assertEquals(SendFailure.Unexplained("boom"), SendFailureCodec.of(RuntimeException("boom")))
        assertEquals(SendFailure.Unexplained("RuntimeException"), SendFailureCodec.of(RuntimeException()))
    }
}
