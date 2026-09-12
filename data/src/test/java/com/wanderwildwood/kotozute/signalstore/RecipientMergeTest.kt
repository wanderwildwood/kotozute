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
            RecipientMerge.Plan.Join(keep = 7, absorb = 9),
            RecipientMerge.plan(byAci = 7, byPni = 9)
        )
    }
}
