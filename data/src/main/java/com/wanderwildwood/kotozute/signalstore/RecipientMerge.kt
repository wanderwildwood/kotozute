package com.wanderwildwood.kotozute.signalstore

/**
 * What to do when a person arrives under ids that may already belong to different rows.
 *
 * A port of the decision in Signal's `RecipientTable.processPnpTupleToChangeSet`, reduced to
 * the shape this app's recipient table can represent. Theirs is larger because it also emits
 * session-switchover and change-number events into the conversation; the *resolution* -- which
 * row survives and which are folded into it -- is what is reproduced here.
 *
 * Written as a pure function for the same reason theirs is: every mistake in it is arithmetic
 * about which row wins, and none of it should need a database, a network or a phone to find.
 *
 * The difficulty it exists for: one person can already be here **three times over** without
 * anything saying so -- once by phone number from contact discovery, once by phone-number
 * identity from a group, once by account id from the account's own records, learned months
 * apart from sources that never mention each other. The moment something arrives naming more
 * than one of those at once is the only moment they can be joined.
 */
internal object RecipientMerge {

    /**
     * One of the rows an incoming person matched, and the account id that row already holds.
     *
     * ⚠ The account id is the whole reason this is a type rather than a `Long`. Without it the
     * decision below cannot tell "a row with nothing in it but a phone number" from "a second,
     * real person", and it was deleting both.
     */
    data class Candidate(val id: Long, val aci: String? = null)

    /** Which identifier is being taken off a row that is not going to be deleted. */
    enum class Held { PNI, E164 }

    /**
     * Take one identifier off [from] and leave the rest of that row alone.
     *
     * Signal's `RemovePni`/`SetPni` pair, and the comment above it: "The PNI record has a
     * different ACI, meaning we need to steal what we need and leave the rest behind."
     */
    data class Steal(val from: Long, val held: Held)

    sealed interface Plan {
        /** Nobody here answers to any of the ids. */
        data object Insert : Plan

        /** One row answers, or several that are already the same row. Fill in what it lacks. */
        data class Update(val id: Long) : Plan

        /**
         * Several rows, and not all of them the same person.
         *
         * [keep] is the row everything folds into. [absorb] are rows safe to destroy -- they
         * hold no account id of their own, or the same one -- and are removed once what they
         * knew has been carried across. [steal] are rows that hold a **different** account id:
         * a wrong identifier is taken off them and given to [keep], and the row itself is left
         * standing.
         *
         * Order matters within a merge: what [keep] already holds wins, because that is what
         * conversations have been using.
         */
        data class Merge(
            val keep: Long,
            val absorb: List<Long>,
            val steal: List<Steal> = emptyList()
        ) : Plan
    }

    /**
     * @param byAci the row found by account id, if any
     * @param byPni the row found by phone-number identity, if any
     * @param byE164 the row found by phone number, if any
     *
     * The account row is kept where there is one, then the number, then the phone-number
     * identity -- Signal's own order. Everything else in this app keys a conversation by the
     * service id it started with, and that is the account id wherever one is known; keeping a
     * different row would mean rewriting those keys, which is the part that goes wrong quietly.
     */
    fun plan(byAci: Candidate?, byPni: Candidate?, byE164: Candidate? = null): Plan {
        val found = listOfNotNull(byAci, byPni, byE164)
        if (found.isEmpty()) return Plan.Insert

        val distinct = found.map { it.id }.distinct()
        if (distinct.size == 1) return Plan.Update(distinct.first())

        // Two or more rows, and this is where it used to go wrong: they were all assumed to be
        // the same person and the losers were deleted.
        //
        // ⚠ They are not necessarily the same person. A stale pni→aci pairing, or a phone
        // number reassigned to somebody new, makes a *second real person's* row the absorb
        // target -- and absorbing it deletes their name, their username and their identity row
        // while every conversation keyed on it silently repoints at the first person. Nothing
        // local can undo that, and nothing about it looks like a fault at the time.
        //
        // Signal refuses. An account id is forever-bound to a row -- "ACI's are forever-bound
        // to a given RecipientId" -- so a row may be destroyed only when it holds no account id
        // of its own, or holds the same one. Otherwise the wrong identifier is taken off it and
        // the row is left alive: `processPossiblePniAciMerge`'s else-branch, which emits
        // RemovePni and SetPni and no Merge at all.
        val keeper = byAci ?: byE164 ?: byPni!!
        val absorb = mutableListOf<Long>()
        val steal = mutableListOf<Steal>()

        fun consider(candidate: Candidate?, held: Held) {
            if (candidate == null || candidate.id == keeper.id) return
            if (candidate.aci == null || candidate.aci == keeper.aci) {
                // Nothing of its own to lose. This is upstream's `pniOnly()` / `e164Only()`
                // case, and the only one in which a row is destroyed.
                if (candidate.id !in absorb) absorb += candidate.id
            } else {
                // A different account id: a different person, whatever the ids suggested.
                steal += Steal(candidate.id, held)
            }
        }

        // Only these two can be losers. Where there is a row by account id it is the keeper, so
        // it is never absorbed and never stolen from.
        consider(byPni, Held.PNI)
        consider(byE164, Held.E164)

        return Plan.Merge(keep = keeper.id, absorb = absorb, steal = steal)
    }
}
