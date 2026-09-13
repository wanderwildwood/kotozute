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

    /** A row holding no account id of its own -- the ordinary case, and safe to absorb. */
    private fun row(id: Long) = RecipientMerge.Candidate(id)

    /** A row that already belongs to somebody, named by their account id. */
    private fun row(id: Long, aci: String) = RecipientMerge.Candidate(id, aci)

    @Test
    fun `somebody nobody knows is new`() {
        assertEquals(RecipientMerge.Plan.Insert, RecipientMerge.plan(incomingAci = null, byAci = null, byPni = null))
    }

    @Test
    fun `known by account only is an update`() {
        assertEquals(RecipientMerge.Plan.Update(7), RecipientMerge.plan(incomingAci = "aci-one", byAci = row(7), byPni = null))
    }

    @Test
    fun `known by phone-number identity only is an update, not a new person`() {
        // This is the moment a PNI-only row learns its account id. Inserting instead would
        // leave the old row keying a conversation nobody could reply in.
        assertEquals(RecipientMerge.Plan.Update(7), RecipientMerge.plan(incomingAci = "aci-one", byAci = null, byPni = row(7)))
    }

    @Test
    fun `already one row is left alone`() {
        assertEquals(RecipientMerge.Plan.Update(7), RecipientMerge.plan(incomingAci = "aci-one", byAci = row(7), byPni = row(7)))
    }

    @Test
    fun `two rows for one person join onto the account row`() {
        // Direction matters and is not arbitrary: threads and messages are keyed by the
        // service id the conversation started with, which is the account id wherever one is
        // known. Keeping the other row would mean rewriting those keys.
        assertEquals(
            RecipientMerge.Plan.Merge(keep = 7, absorb = listOf(9)),
            RecipientMerge.plan(incomingAci = "aci-one", byAci = row(7), byPni = row(9))
        )
    }
    @Test
    fun `three rows for one person all fold onto the account row`() {
        // The case the whole table exists for: found by number from discovery, by phone-number
        // identity from a group, and by account id from the account's own records -- months
        // apart, from sources that never mention each other.
        val plan = RecipientMerge.plan(incomingAci = "aci-one", byAci = row(7), byPni = row(9), byE164 = row(11))
        assertEquals(RecipientMerge.Plan.Merge(keep = 7, absorb = listOf(9, 11)), plan)
    }

    @Test
    fun `with no account row the number wins over the phone-number identity`() {
        // Signal's own order. A number is the more useful handle of the two when neither is
        // an account id, and it is what an address book can put a name to.
        assertEquals(
            RecipientMerge.Plan.Merge(keep = 11, absorb = listOf(9)),
            RecipientMerge.plan(incomingAci = "aci-one", byAci = null, byPni = row(9), byE164 = row(11))
        )
    }

    @Test
    fun `rows that are already the same row are left alone`() {
        assertEquals(RecipientMerge.Plan.Update(7), RecipientMerge.plan("aci-one", row(7), row(7), row(7)))
        assertEquals(RecipientMerge.Plan.Update(7), RecipientMerge.plan("aci-one", row(7), null, row(7)))
    }

    @Test
    fun `a row holding a different account id is never deleted`() {
        // ⚠ The fault this table exists to prevent, and the one it used to have.
        //
        // A stale pni-to-aci pairing, or a phone number reassigned to somebody new, makes a
        // second *real person's* row look like a duplicate. Absorbing it deletes their name,
        // their username and their identity row, and every conversation keyed on it silently
        // starts pointing at the first person. Nothing local can undo it.
        //
        // Signal refuses: an account id is forever-bound to a row. The wrong identifier is
        // taken back and the row is left standing.
        val plan = RecipientMerge.plan(
            incomingAci = "aci-first-person",
            byAci = row(7, "aci-first-person"),
            byPni = row(9, "aci-somebody-else")
        )
        assertEquals(
            RecipientMerge.Plan.Merge(
                keep = 7,
                absorb = emptyList(),
                steal = listOf(RecipientMerge.Steal(from = 9, held = RecipientMerge.Held.PNI))
            ),
            plan
        )
    }

    @Test
    fun `a row holding the same account id is still safe to absorb`() {
        // Same person, reached two ways. Nothing is lost by folding these together, and this
        // is the case the merge is actually for.
        assertEquals(
            RecipientMerge.Plan.Merge(keep = 7, absorb = listOf(9)),
            RecipientMerge.plan(incomingAci = "aci-one", byAci = row(7, "aci-one"), byPni = row(9, "aci-one"))
        )
    }

    @Test
    fun `a number belonging to somebody else is taken back, not merged`() {
        // The reassigned-number case, with no account row in the middle of it.
        val plan = RecipientMerge.plan(
            incomingAci = "aci-first-person",
            byAci = row(7, "aci-first-person"),
            byPni = null,
            byE164 = row(11, "aci-somebody-else")
        )
        assertEquals(
            RecipientMerge.Plan.Merge(
                keep = 7,
                absorb = emptyList(),
                steal = listOf(RecipientMerge.Steal(from = 11, held = RecipientMerge.Held.E164))
            ),
            plan
        )
    }

    @Test
    fun `one row is absorbed and another spared in the same decision`() {
        // Three rows, and they are not all one person: the phone-number row has nothing of its
        // own and folds in; the number row belongs to somebody else and does not.
        val plan = RecipientMerge.plan(
            incomingAci = "aci-first-person",
            byAci = row(7, "aci-first-person"),
            byPni = row(9),
            byE164 = row(11, "aci-somebody-else")
        )
        assertEquals(
            RecipientMerge.Plan.Merge(
                keep = 7,
                absorb = listOf(9),
                steal = listOf(RecipientMerge.Steal(from = 11, held = RecipientMerge.Held.E164))
            ),
            plan
        )
    }

    @Test
    fun `with no account row of our own a conflicting row is still spared`() {
        // The keeper holds no account id, so it cannot match; the loser holds one. Absorbing
        // would delete a person to make room for a row that knows less than they do.
        val plan = RecipientMerge.plan(
            incomingAci = "aci-first-person",
            byAci = null,
            byPni = row(9, "aci-somebody-else"),
            byE164 = row(11)
        )
        assertEquals(
            RecipientMerge.Plan.Merge(
                keep = 11,
                absorb = emptyList(),
                steal = listOf(RecipientMerge.Steal(from = 9, held = RecipientMerge.Held.PNI))
            ),
            plan
        )
    }

    @Test
    fun `a recycled number does not overwrite its previous owner`() {
        // ⚠ The other half of the same fault, and the one a merge guard does not catch,
        // because there is nothing to merge: only ONE row answers.
        //
        // Alice's number is reassigned to Bob. A contact for Bob arrives; Bob has no row; the
        // only row holding that number is Alice's. Treating it as "the row for this number"
        // and updating it wrote Bob's name, profile key and username into Alice's row -- which
        // still held Alice's account id, so the conversation went on addressing Alice while
        // showing Bob's name and encrypting to Bob's profile key.
        //
        // Signal takes the number off Alice and gives Bob a record of his own. Alice loses
        // only the number that is no longer hers.
        val plan = RecipientMerge.plan(
            incomingAci = "aci-bob",
            byAci = null,
            byPni = null,
            byE164 = row(11, "aci-alice")
        )
        assertEquals(
            RecipientMerge.Plan.InsertAfterSteal(
                listOf(RecipientMerge.Steal(from = 11, held = RecipientMerge.Held.E164))
            ),
            plan
        )
    }

    @Test
    fun `a phone-number identity that has moved on does not overwrite its old owner either`() {
        val plan = RecipientMerge.plan(
            incomingAci = "aci-bob",
            byAci = null,
            byPni = row(9, "aci-alice")
        )
        assertEquals(
            RecipientMerge.Plan.InsertAfterSteal(
                listOf(RecipientMerge.Steal(from = 9, held = RecipientMerge.Held.PNI))
            ),
            plan
        )
    }

    @Test
    fun `a number on a row with no owner is still just an update`() {
        // The ordinary case, and the one that must not be broken by the guard above: a row
        // holding a number and nothing else is a placeholder for that number, and this is the
        // contact that finally says who it belongs to.
        assertEquals(
            RecipientMerge.Plan.Update(11),
            RecipientMerge.plan(incomingAci = "aci-bob", byAci = null, byPni = null, byE164 = row(11))
        )
    }

    @Test
    fun `with no account id arriving nothing is assumed to have moved`() {
        // A contact that names no account id cannot disagree with one. Upstream has the same
        // limit on the same tuple, and guessing here would refuse to record real contacts.
        assertEquals(
            RecipientMerge.Plan.Update(11),
            RecipientMerge.plan(incomingAci = null, byAci = null, byPni = null, byE164 = row(11, "aci-alice"))
        )
    }

    @Test
    fun `two rows owned by two other people are both spared`() {
        // Neither row is this person's, so neither may be written into or deleted.
        val plan = RecipientMerge.plan(
            incomingAci = "aci-bob",
            byAci = null,
            byPni = row(9, "aci-alice"),
            byE164 = row(11, "aci-carol")
        )
        assertEquals(
            RecipientMerge.Plan.InsertAfterSteal(
                listOf(
                    RecipientMerge.Steal(from = 9, held = RecipientMerge.Held.PNI),
                    RecipientMerge.Steal(from = 11, held = RecipientMerge.Held.E164)
                )
            ),
            plan
        )
    }

    @Test
    fun `found only by number is an update, not a new person`() {
        assertEquals(RecipientMerge.Plan.Update(11), RecipientMerge.plan("aci-one", null, null, row(11)))
    }

}
