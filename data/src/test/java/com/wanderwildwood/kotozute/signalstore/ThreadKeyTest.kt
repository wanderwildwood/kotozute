package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which conversation a message belongs to.
 *
 * These mirror the bridge's `normalize_test.go` deliberately, case for case. The two rails
 * write into the same threads, so a rule that differs between them does not fail -- it
 * quietly produces a second thread for a conversation that already has one, and the only
 * symptom is a duplicate in the inbox a long time later.
 *
 * Every case here is a bug that happened, on one rail or the other.
 */
class ThreadKeyTest {

    private val selfAci = "00000000-0000-4000-8000-000000005e1f"
    private val selfE164 = "+15550000000"
    private val theirAci = "11111111-2222-4333-8444-555555555555"

    private fun key(
        outgoing: Boolean = false,
        uuid: String = "",
        number: String = "",
        groupId: String = ""
    ) = ContentNormalizer.threadKeyFor(outgoing, uuid, number, groupId, selfAci, selfE164)

    @Test
    fun `an incoming message keys on its sender`() {
        assertEquals("direct:$theirAci", key(uuid = theirAci))
    }

    @Test
    fun `our own send keys on the recipient, not on us`() {
        assertEquals("direct:$theirAci", key(outgoing = true, uuid = theirAci))
    }

    @Test
    fun `a group message keys on the group, whoever sent it`() {
        assertEquals("group:abc", key(uuid = theirAci, groupId = "abc"))
        assertEquals("group:abc", key(outgoing = true, uuid = theirAci, groupId = "abc"))
    }

    @Test
    fun `a sent sync with no destination at all is note to self`() {
        assertEquals("direct:$selfAci", key(outgoing = true))
    }

    /**
     * The protobuf populates the destination with our own number where signal-cli sends
     * nothing, so absence alone is not the test. This filed Note to Self under a second
     * thread key until it was measured.
     */
    @Test
    fun `a sent sync addressed to our own number is note to self, keyed on the aci`() {
        assertEquals("direct:$selfAci", key(outgoing = true, number = selfE164))
    }

    @Test
    fun `a sent sync addressed to our own aci is note to self`() {
        assertEquals("direct:$selfAci", key(outgoing = true, uuid = selfAci))
    }

    /**
     * The inverse mistake, and the more expensive one: testing the uuid alone filed a message
     * to a real person as Note to Self whenever only their number was known -- and a reply in
     * that thread then went to yourself.
     */
    @Test
    fun `a sent sync with only someone else's number is not note to self`() {
        assertEquals("direct:+15551234567", key(outgoing = true, number = "+15551234567"))
    }

    @Test
    fun `an aci is preferred over a number when both are present`() {
        assertEquals("direct:$theirAci", key(uuid = theirAci, number = "+15551234567"))
    }

    @Test
    fun `nothing to hang a thread on yields no thread`() {
        assertNull(key(outgoing = false))
    }

    @Test
    fun `a message identity is its author and timestamp`() {
        assertEquals("$theirAci:1700000000000", ContentNormalizer.messageIdFor(theirAci, "+1", 1700000000000))
    }

    /** The number is the fallback, so a sender known only by number still has a stable id. */
    @Test
    fun `a message identity falls back to the number`() {
        assertEquals("+15551234567:17", ContentNormalizer.messageIdFor("", "+15551234567", 17))
    }
}
