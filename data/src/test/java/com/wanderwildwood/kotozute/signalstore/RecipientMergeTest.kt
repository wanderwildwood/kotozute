package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which row wins when one person turns out to be two.
 *
 * The case worth the whole table: somebody discovered by phone number months ago, and the same
 * person known by account id from the account's own records. Nothing said they were one person
 * until something named both ids at once.
 */
class RecipientMergeTest {

    @Test
    fun `somebody nobody knows is new`() {
        assertEquals(RecipientMerge.Plan.Insert, RecipientMerge.plan(byAci = null, byPni = null))
    }

    @Test
    fun `known by account only is an update`() {
        assertEquals(RecipientMerge.Plan.Update(7), RecipientMerge.plan(byAci = 7, byPni = null))
    }

    @Test
    fun `known by phone-number identity only is an update, not a new person`() {
        // This is the moment a PNI-only row learns its account id. Inserting instead would
        // leave the old row keying a conversation nobody could reply in.
        assertEquals(RecipientMerge.Plan.Update(7), RecipientMerge.plan(byAci = null, byPni = 7))
    }

    @Test
    fun `already one row is left alone`() {
        assertEquals(RecipientMerge.Plan.Update(7), RecipientMerge.plan(byAci = 7, byPni = 7))
    }

    @Test
    fun `two rows for one person join onto the account row`() {
        // Direction matters and is not arbitrary: threads and messages are keyed by the
        // service id the conversation started with, which is the account id wherever one is
        // known. Keeping the other row would mean rewriting those keys.
        assertEquals(
            RecipientMerge.Plan.Merge(keep = 7, absorb = listOf(9)),
            RecipientMerge.plan(byAci = 7, byPni = 9)
        )
    }
    @Test
    fun `three rows for one person all fold onto the account row`() {
        // The case the whole table exists for: found by number from discovery, by phone-number
        // identity from a group, and by account id from the account's own records -- months
        // apart, from sources that never mention each other.
        val plan = RecipientMerge.plan(byAci = 7, byPni = 9, byE164 = 11)
        assertEquals(RecipientMerge.Plan.Merge(keep = 7, absorb = listOf(9, 11)), plan)
    }

    @Test
    fun `with no account row the number wins over the phone-number identity`() {
        // Signal's own order. A number is the more useful handle of the two when neither is
        // an account id, and it is what an address book can put a name to.
        assertEquals(
            RecipientMerge.Plan.Merge(keep = 11, absorb = listOf(9)),
            RecipientMerge.plan(byAci = null, byPni = 9, byE164 = 11)
        )
    }

    @Test
    fun `rows that are already the same row are left alone`() {
        assertEquals(RecipientMerge.Plan.Update(7), RecipientMerge.plan(7, 7, 7))
        assertEquals(RecipientMerge.Plan.Update(7), RecipientMerge.plan(7, null, 7))
    }

    @Test
    fun `found only by number is an update, not a new person`() {
        assertEquals(RecipientMerge.Plan.Update(11), RecipientMerge.plan(null, null, 11))
    }

}
