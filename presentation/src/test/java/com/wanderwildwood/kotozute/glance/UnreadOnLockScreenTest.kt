package com.wanderwildwood.kotozute.glance

import com.wanderwildwood.kotozute.glance.UnreadOnLockScreen.Companion.chatsWaiting
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The lock screen counts conversations waiting to be read, not messages, and leaves muted ones
 * out. "8 Signal" for eight messages in one muted group is the line this exists to stop.
 */
class UnreadOnLockScreenTest {

    @Test
    fun `eight messages in one conversation are one`() {
        assertEquals(1, chatsWaiting(mapOf("group:A" to 8)) { false })
    }

    @Test
    fun `each conversation with something unread is one`() {
        assertEquals(3, chatsWaiting(mapOf(1L to 1, 2L to 5, 3L to 2)) { false })
    }

    @Test
    fun `a muted conversation is not counted`() {
        assertEquals(0, chatsWaiting(mapOf("group:A" to 8)) { it == "group:A" })
        assertEquals(1, chatsWaiting(mapOf("group:A" to 8, "direct:B" to 1)) { it == "group:A" })
    }

    @Test
    fun `a conversation with nothing unread is not counted`() {
        assertEquals(1, chatsWaiting(mapOf(1L to 0, 2L to 4)) { false })
    }

    @Test
    fun `nothing is nothing`() {
        assertEquals(0, chatsWaiting(emptyMap<Long, Int>()) { false })
    }
}
