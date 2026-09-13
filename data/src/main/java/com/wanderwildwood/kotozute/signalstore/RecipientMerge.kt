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

        /**
         * Every row that answered belongs to somebody else, so this person gets a row of
         * their own and the stale identifiers are taken back first.
         *
         * ⚠ The recycled-number case, and the one that used to be silent. A number is given
         * to a new person; a contact for that new person arrives; the only row holding the
         * number is the *previous owner's*. Treating that as "the row for this number" and
         * writing the new name into it overwrote a real person -- their name, their profile
         * key and their username -- on a row that still carried the old owner's account id, so
         * the conversation went on pointing at one person while showing another's name and
         * encrypting to another's profile key.
         *
         * Signal removes the number from the old owner and gives it to a record of its own:
         * `processPossibleE164AciMerge`'s "E164RecordHasNonMatchingPni" branch, which emits
         * RemoveE164 and SetE164 and no merge. The old owner loses only the number that is no
         * longer theirs.
         */
        data class InsertAfterSteal(val steal: List<Steal>) : Plan

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
    fun plan(
        incomingAci: String?,
        byAci: Candidate?,
        byPni: Candidate?,
        byE164: Candidate? = null
    ): Plan {
        val found = listOfNotNull(byAci, byPni, byE164)
        if (found.isEmpty()) return Plan.Insert

        /**
         * Whether this row is somebody else's.
         *
         * Only answerable when the arriving contact names an account id: without one there is
         * nothing to disagree with, and a row found by number is the best guess available --
         * which is also all upstream can do with the same tuple.
         */
        fun belongsToSomebodyElse(candidate: Candidate): Boolean =
            incomingAci != null && candidate.aci != null && candidate.aci != incomingAci

        // Rows found by an identifier that has moved on. They are not this person, whatever
        // the lookup suggested, and none of them may be written into or deleted.
        val theirs = buildList {
            byPni?.takeIf { belongsToSomebodyElse(it) }?.let { add(Steal(it.id, Held.PNI)) }
            byE164?.takeIf { belongsToSomebodyElse(it) }?.let { add(Steal(it.id, Held.E164)) }
        }
        val ours = listOfNotNull(
            byAci,
            byPni?.takeUnless { belongsToSomebodyElse(it) },
            byE164?.takeUnless { belongsToSomebodyElse(it) }
        )

        // Everything that answered belongs to somebody else. Take the stale identifiers back
        // and give this person a row of their own.
        if (ours.isEmpty()) return Plan.InsertAfterSteal(theirs)

        val distinct = ours.map { it.id }.distinct()
        if (distinct.size == 1 && theirs.isEmpty()) return Plan.Update(distinct.first())
        if (distinct.size == 1) {
            return Plan.Merge(keep = distinct.first(), absorb = emptyList(), steal = theirs)
        }

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
        // Signal's order, and for its reason: account id, then number, then phone-number
        // identity. ⚠ Not simply "the first row that answered" -- that reads in lookup order,
        // which puts the phone-number identity ahead of the number and keeps the wrong row.
        val keeper = ours.firstOrNull { it.id == byAci?.id }
            ?: ours.firstOrNull { it.id == byE164?.id }
            ?: ours.first()
        val absorb = mutableListOf<Long>()
        val alsoTheirs = mutableListOf<Steal>()

        // ⚠ Two different tests, and both are needed.
        //
        // The one above asks "does this row belong to somebody other than the person
        // arriving?", which only has an answer when the arriving contact names an account id.
        // This one asks "does this row hold an account id other than the keeper's?", which has
        // an answer even when nothing is arriving with one -- two rows found by two different
        // identifiers can each already belong to a different person, and folding either into
        // the other would destroy one of them.
        fun consider(candidate: Candidate?, held: Held) {
            if (candidate == null || candidate.id == keeper.id) return
            if (theirs.any { it.from == candidate.id }) return
            if (candidate.aci == null || candidate.aci == keeper.aci) {
                // Nothing of its own to lose. Upstream's `pniOnly()` / `e164Only()` case, and
                // the only one in which a row is destroyed.
                if (candidate.id !in absorb) absorb += candidate.id
            } else {
                alsoTheirs += Steal(candidate.id, held)
            }
        }

        consider(byPni, Held.PNI)
        consider(byE164, Held.E164)

        return Plan.Merge(keep = keeper.id, absorb = absorb, steal = theirs + alsoTheirs)
    }
}
