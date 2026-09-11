package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The blocked list, and who a reaction names.
 *
 * A blocked-list sync **replaces** what the account holds — there is no "block one more" in
 * Signal's protocol. So every one of these is a way to unblock somebody by accident, and the
 * symptom is a message arriving months later from a person who was supposed to be gone.
 */
class SignalBlockListTest {

    private val ada = "11111111-1111-4111-8111-111111111111"
    private val grace = "22222222-2222-4222-8222-222222222222"
    private val me = "00000000-0000-4000-8000-000000000000"
    private val now = 1_700_000_000_000

    private fun blocked(aci: String, at: Long = 1) = SignalBlockStore.Blocked(aci, null, at)

    @Test
    fun `blocking somebody keeps everyone already blocked`() {
        val after = SignalBlockList.edit(listOf(blocked(ada)), grace, blocked = true, now = now)

        assertEquals(setOf(ada, grace), after.mapNotNull { it.aci }.toSet())
    }

    @Test
    fun `unblocking takes out one name and no others`() {
        val after = SignalBlockList.edit(
            listOf(blocked(ada), blocked(grace)), ada, blocked = false, now = now
        )

        assertEquals(listOf(grace), after.mapNotNull { it.aci })
    }

    @Test
    fun `blocking somebody already blocked does not list them twice`() {
        val after = SignalBlockList.edit(listOf(blocked(ada)), ada, blocked = true, now = now)

        assertEquals(1, after.size)
        // And the timestamp is the new one: this is a fresh statement of the block.
        assertEquals(now, after.single().blockedAt)
    }

    @Test
    fun `a service id written in either case is the same person`() {
        val after = SignalBlockList.edit(
            listOf(blocked(ada.uppercase())), ada, blocked = false, now = now
        )

        // Matched case-insensitively, or unblocking silently leaves them blocked and
        // re-blocking lists them twice.
        assertTrue(after.isEmpty())
    }

    @Test
    fun `an entry carried across keeps the time it was blocked`() {
        val after = SignalBlockList.edit(listOf(blocked(ada, at = 42)), grace, blocked = true, now = now)

        assertEquals(42, after.first { it.aci == ada }.blockedAt)
    }

    @Test
    fun `unblocking the only blocked person leaves an empty list, which is a real answer`() {
        val after = SignalBlockList.edit(listOf(blocked(ada)), ada, blocked = false, now = now)

        assertTrue(after.isEmpty())
    }

    @Test
    fun `a reaction to somebody else's message names them`() {
        assertEquals(
            ada,
            SignalBlockList.targetAuthor(outgoing = false, senderUuid = ada, senderNumber = "", selfAci = me)
        )
    }

    @Test
    fun `a reaction to our own message names this account`() {
        // The bridge filled this in from its own side, so it was left empty here. On this
        // rail an empty author is a reaction Signal cannot place.
        assertEquals(
            me,
            SignalBlockList.targetAuthor(outgoing = true, senderUuid = "", senderNumber = "", selfAci = me)
        )
    }

    @Test
    fun `a sender known only by number is named by it`() {
        assertEquals(
            "+15550001111",
            SignalBlockList.targetAuthor(
                outgoing = false, senderUuid = "", senderNumber = "+15550001111", selfAci = me
            )
        )
    }
}
