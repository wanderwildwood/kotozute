package com.wanderwildwood.kotozute.signal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge computed a deadline for every disappearing message, stored it, sent it, and
 * swept its own copy on time -- and the phone read none of it. The message the bridge
 * deleted was the only copy that was ever going to go.
 *
 * These pin the wire shape, because that is where it was lost.
 */
class BridgeMessageParsingTest {

    private val client = BridgeClient(
        BridgeConfig(host = "127.0.0.1", port = 8422, token = "t", fingerprint = "AB".repeat(32))
    )

    private fun wire(extra: String = ""): JSONObject = JSONObject(
        """{"id":"a:1","seq":7,"threadKey":"direct:x","ts":1700000000000,
            "senderUuid":"u","senderNumber":"+1","outgoing":false,"body":"hi",
            "groupId":"","quoteTs":0,"read":false,"source":"live","attachments":[]
            ${if (extra.isEmpty()) "" else ",$extra"}}"""
    )

    @Test
    fun `a deadline on the wire reaches the app`() {
        val m = client.parseMessage(wire(""""expiresAt":1700000030000,"expiresInSeconds":30"""))
        assertEquals(1700000030000L, m.expiresAt)
        assertEquals(30L, m.expiresInSeconds)
    }

    @Test
    fun `a message with no timer has no deadline`() {
        val m = client.parseMessage(wire())
        assertEquals(0L, m.expiresAt)
        assertEquals(0L, m.expiresInSeconds)
        assertFalse(m.viewOnce)
    }

    @Test
    fun `view-once is carried`() {
        assertTrue(client.parseMessage(wire(""""viewOnce":true""")).viewOnce)
        assertFalse(client.parseMessage(wire(""""viewOnce":false""")).viewOnce)
    }

    @Test
    fun `the rest of the message still parses`() {
        val m = client.parseMessage(wire(""""expiresAt":1700000030000"""))
        assertEquals("a:1", m.id)
        assertEquals(7L, m.seq)
        assertEquals("direct:x", m.threadKey)
        assertEquals("hi", m.body)
        assertEquals(1700000000000L, m.ts)
    }
}
