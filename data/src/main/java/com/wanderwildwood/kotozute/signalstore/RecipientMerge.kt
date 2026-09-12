package com.wanderwildwood.kotozute.signalstore

/**
 * What to do when a person arrives under ids that may already be here.
 *
 * A scaled-down statement of what Signal's `processPnpTupleToChangeSet` decides. Theirs
 * handles every case a phone number can produce -- a number moving to a different person, an
 * account re-registering, a self-change -- against a table with the same shape. This handles
 * the cases this app can actually reach today, and is written as a pure function for the same
 * reason theirs is: every mistake in it is arithmetic about which row wins, and none of it
 * should need a database, a network or a phone to find.
 *
 * The whole difficulty is that a person can be here **twice** and not look like it -- once by
 * account id, once by phone-number identity, learned from different sources months apart. The
 * moment something names both at once is the only moment they can be joined.
 */
internal object RecipientMerge {

    sealed interface Plan {
        /** Nobody here answers to either id. */
        data object Insert : Plan

        /** Exactly one row answers. Fill in whatever it did not know. */
        data class Update(val id: Long) : Plan

        /**
         * Two rows, one person.
         *
         * [keep] is the row the conversation is already keyed by; [absorb] is folded into it
         * and removed. What [absorb] knew must be carried across **before** it goes: losing a
         * name or a number to a merge is a worse bug than the duplicate it fixes.
         */
        data class Join(val keep: Long, val absorb: Long) : Plan
    }

    /**
     * @param byAci the row found by account id, if any
     * @param byPni the row found by phone-number identity, if any
     *
     * The account row is kept when there are two. Everything else in this app -- threads,
     * messages, read state -- is keyed by the service id a conversation was started with, and
     * that is the account id wherever one is known. Keeping the other row would mean rewriting
     * those keys, which is the part that goes wrong quietly.
     */
    fun plan(byAci: Long?, byPni: Long?): Plan = when {
        byAci == null && byPni == null -> Plan.Insert
        byAci == null -> Plan.Update(byPni!!)
        byPni == null -> Plan.Update(byAci)
        byAci == byPni -> Plan.Update(byAci)
        else -> Plan.Join(keep = byAci, absorb = byPni)
    }
}
