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

    sealed interface Plan {
        /** Nobody here answers to any of the ids. */
        data object Insert : Plan

        /** One row answers, or several that are already the same row. Fill in what it lacks. */
        data class Update(val id: Long) : Plan

        /**
         * Several rows, one person.
         *
         * [keep] is the row everything else folds into; [absorb] are removed once what they
         * knew has been carried across. Order matters: what [keep] already holds wins, because
         * that is what conversations have been using.
         */
        data class Merge(val keep: Long, val absorb: List<Long>) : Plan
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
    fun plan(byAci: Long?, byPni: Long?, byE164: Long? = null): Plan {
        val found = listOfNotNull(byAci, byPni, byE164)
        if (found.isEmpty()) return Plan.Insert

        val distinct = found.distinct()
        if (distinct.size == 1) return Plan.Update(distinct.first())

        // Two or more rows are the same person. Signal's order, and for its reason.
        val keep = byAci ?: byE164 ?: byPni!!
        return Plan.Merge(keep = keep, absorb = distinct.filter { it != keep })
    }
}
