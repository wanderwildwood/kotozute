package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.whispersystems.signalservice.internal.push.CallMessage.Hangup
import org.whispersystems.signalservice.internal.push.SyncMessage.CallEvent

/**
 * How a Signal call this phone cannot answer is recorded.
 *
 * Both ways of getting it wrong are bad in their own way. A call answered on the other phone
 * written down as missed sends somebody to call back a person they have just spoken to; a
 * missed call written down as anything else is the silence this exists to end.
 */
class SignalCallsTest {

    @Test
    fun `a caller giving up is a missed call, and answered elsewhere is not`() {
        assertEquals(CallOutcome.MISSED, SignalCalls.outcomeOfHangup(Hangup.Type.HANGUP_NORMAL))
        assertEquals(CallOutcome.MISSED, SignalCalls.outcomeOfHangup(null))
        assertEquals(CallOutcome.MISSED, SignalCalls.outcomeOfHangup(Hangup.Type.HANGUP_BUSY))
        assertEquals(CallOutcome.ANSWERED_ELSEWHERE, SignalCalls.outcomeOfHangup(Hangup.Type.HANGUP_ACCEPTED))
        assertEquals(CallOutcome.DECLINED_ELSEWHERE, SignalCalls.outcomeOfHangup(Hangup.Type.HANGUP_DECLINED))
    }

    @Test
    fun `another device's report settles one-to-one calls only`() {
        assertEquals(
            CallOutcome.ANSWERED_ELSEWHERE,
            SignalCalls.outcomeOfEvent(CallEvent.Type.AUDIO_CALL, CallEvent.Direction.INCOMING, CallEvent.Event.ACCEPTED)
        )
        assertEquals(
            CallOutcome.DECLINED_ELSEWHERE,
            SignalCalls.outcomeOfEvent(CallEvent.Type.VIDEO_CALL, CallEvent.Direction.INCOMING, CallEvent.Event.NOT_ACCEPTED)
        )
        assertEquals(
            CallOutcome.OUTGOING,
            SignalCalls.outcomeOfEvent(CallEvent.Type.AUDIO_CALL, CallEvent.Direction.OUTGOING, CallEvent.Event.NOT_ACCEPTED)
        )
        assertNull("group calls are not recorded here",
            SignalCalls.outcomeOfEvent(CallEvent.Type.GROUP_CALL, CallEvent.Direction.INCOMING, CallEvent.Event.ACCEPTED))
        assertNull("a deletion is not a call",
            SignalCalls.outcomeOfEvent(CallEvent.Type.AUDIO_CALL, CallEvent.Direction.INCOMING, CallEvent.Event.DELETE))
        assertNull(SignalCalls.outcomeOfEvent(CallEvent.Type.AUDIO_CALL, CallEvent.Direction.INCOMING, CallEvent.Event.OBSERVED))
    }

    @Test
    fun `a call rings for two minutes`() {
        val at = 1_790_000_000_000L
        assertFalse(SignalCalls.expired(at, at + SignalCalls.MAX_RING_MS - 1))
        assertTrue(SignalCalls.expired(at, at + SignalCalls.MAX_RING_MS))
    }

    @Test
    fun `a call event names the person by their id's bytes`() {
        val uuid = java.util.UUID.fromString("0f2d5c1a-3b4e-4f60-8a71-92b3c4d5e6f7")
        val bytes = java.nio.ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
        assertEquals(uuid.toString(), SignalCalls.aciOf(bytes))
        assertNull(SignalCalls.aciOf(ByteArray(15)))
        assertNull(SignalCalls.aciOf(null))
    }

    // --- what other devices are told, and what they tell us ------------------------------------

    @Test
    fun `a delete is a one-to-one call's, and nothing else is`() {
        assertTrue(SignalCalls.isDelete(CallEvent.Type.AUDIO_CALL, CallEvent.Event.DELETE))
        assertTrue(SignalCalls.isDelete(CallEvent.Type.VIDEO_CALL, CallEvent.Event.DELETE))
        assertFalse(SignalCalls.isDelete(CallEvent.Type.GROUP_CALL, CallEvent.Event.DELETE))
        assertFalse(SignalCalls.isDelete(CallEvent.Type.AUDIO_CALL, CallEvent.Event.ACCEPTED))
        // The control: a delete is not mistaken for a call settling.
        assertNull(SignalCalls.outcomeOfEvent(CallEvent.Type.AUDIO_CALL, CallEvent.Direction.INCOMING, CallEvent.Event.DELETE))
    }

    /**
     * ⚠ The receipt rule. A call line's timestamp is a call's, not a message anybody sent, so a
     * read receipt for it names a message that does not exist. Only real messages pass.
     */
    @Test
    fun `lines this phone wrote are told apart from messages`() {
        assertTrue(SignalCalls.isOwnLine("call:1234"))
        assertTrue(SignalCalls.isOwnLine("groupcall:abc"))
        assertTrue(SignalCalls.isOwnLine("local:x"))
        assertFalse(SignalCalls.isOwnLine("5f0c7b1e-0000-4000-8000-000000000001:1696350000000"))
        assertTrue(SignalCalls.isCallLine("groupcall:abc"))
        assertFalse(SignalCalls.isCallLine("local:x"))
    }

    @Test
    fun `a call line gives up its call id, and nothing else does`() {
        assertEquals(1234L, SignalCalls.callIdOf("call:1234"))
        assertEquals(-5L, SignalCalls.callIdOf("call:-5"))
        assertNull(SignalCalls.callIdOf("groupcall:1234"))
        assertNull(SignalCalls.callIdOf("call:"))
        assertNull(SignalCalls.callIdOf("someone:1234"))
    }

    @Test
    fun `a conversation id goes both ways, and a group is its own`() {
        val aci = "5f0c7b1e-0000-4000-8000-000000000001"
        val bytes = SignalCalls.conversationIdOf(aci)!!
        assertEquals(16, bytes.size)
        assertEquals("direct:$aci", SignalCalls.threadKeyOf(bytes))
        val group = ByteArray(32) { it.toByte() }
        assertEquals(
            "group:" + java.util.Base64.getEncoder().encodeToString(group),
            SignalCalls.threadKeyOf(group)
        )
        assertNull(SignalCalls.threadKeyOf(ByteArray(20)))
        assertNull(SignalCalls.threadKeyOf(null))
        assertNull(SignalCalls.conversationIdOf("not an id"))
    }
}
