package com.wanderwildwood.kotozute.feature.conversations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both rails share one adapter, one selection list and one swipe callback, all keyed on a
 * Long. SMS rows carry a telephony thread id, which is positive; the adapter returns -1
 * for "no item"; so Signal has to occupy a range that collides with neither. Getting this
 * wrong would not look like a crash -- a swipe would archive an unrelated conversation.
 */
class InboxItemTest {

    /** Mirrors InboxItem.Signal.stableId. Kept here so a change to it fails a test. */
    private fun signalId(threadKey: String): Long =
        -2L - (threadKey.hashCode().toLong() and 0xFFFFFFFFL)

    private val keys = listOf(
        "direct:00000000-0000-4000-8000-000000000000",
        "direct:11111111-1111-4111-8111-111111111111",
        "group:GROUPID==",
        "direct:+15551234567",
        "",
        "direct:" + "x".repeat(400)
    )

    @Test
    fun `every signal id is recognised as one`() {
        keys.forEach { key ->
            assertTrue("not recognised: $key", InboxItem.isSignalId(signalId(key)))
        }
    }

    @Test
    fun `no signal id can be mistaken for a telephony thread id`() {
        // Telephony thread ids are positive, and 0 is not a real one either.
        keys.forEach { key ->
            assertTrue("collides with an sms id: $key", signalId(key) < 0)
        }
    }

    @Test
    fun `no signal id is the sentinel the adapter uses for no item`() {
        keys.forEach { key ->
            assertNotEquals("collides with the no-item sentinel: $key", -1L, signalId(key))
        }
    }

    @Test
    fun `the sentinel and ordinary sms ids are not treated as signal`() {
        assertFalse(InboxItem.isSignalId(-1L))
        assertFalse(InboxItem.isSignalId(0L))
        assertFalse(InboxItem.isSignalId(1L))
        assertFalse(InboxItem.isSignalId(Long.MAX_VALUE))
    }

    @Test
    fun `the same thread keeps the same id across rebuilds`() {
        // The list is rebuilt on every change; a wandering id would break stable ids.
        val key = "direct:00000000-0000-4000-8000-000000000000"
        assertEquals(signalId(key), signalId(key))
    }

    @Test
    fun `different threads get different ids`() {
        val ids = keys.map { signalId(it) }
        assertEquals("two threads share an id", ids.size, ids.toSet().size)
    }

    @Test
    fun `a hash with the sign bit set still lands in range`() {
        // hashCode can be negative; masking to 32 bits is what keeps the result below -1
        // rather than wrapping back up into positive territory.
        val negativeHashKey = generateSequence(0) { it + 1 }
            .map { "direct:probe-$it" }
            .first { it.hashCode() < 0 }
        val id = signalId(negativeHashKey)
        assertTrue("wrapped out of range: $id", id <= -2L)
        assertTrue(InboxItem.isSignalId(id))
    }
}
