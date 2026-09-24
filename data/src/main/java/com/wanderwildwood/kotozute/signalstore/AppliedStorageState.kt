package com.wanderwildwood.kotozute.signalstore

/**
 * Which of the account's muted and archived answers to apply, and which to leave alone.
 *
 * Signal applies a storage record only when its id is new to it -- `StorageSyncJob` reads
 * just `idDifference.remoteOnlyIds` -- and the id changes whenever the record's content does.
 * So an id seen before is the account repeating itself, and repeating itself must not undo a
 * change made on this phone since. Applying every record on every read is what re-archived
 * an unarchived conversation at the next launch (forum #47).
 */
internal object AppliedStorageState {

    class Plan(
        /** States from records that are new or changed. */
        val apply: List<SignalStorageService.ConversationState>,
        /** What to remember as applied: every record whose conversation is on this phone. */
        val applied: Set<String>
    )

    /**
     * @param alreadyApplied what the previous read remembered. Empty means every record is
     *   applied once, which is how this behaved before it existed.
     * @param hasConversation whether the phone has a thread for a key. A record for a
     *   conversation not here yet is neither applied nor remembered, so its state lands once
     *   the conversation exists.
     */
    fun plan(
        states: List<SignalStorageService.ConversationState>,
        alreadyApplied: Set<String>,
        hasConversation: (String) -> Boolean
    ): Plan {
        val present = states.filter { hasConversation(it.threadKey) }
        return Plan(
            apply = present.filter { it.recordId !in alreadyApplied },
            // Replaced wholesale, not added to: an id the manifest no longer names has since
            // changed or gone and will never be offered again.
            applied = present.mapTo(mutableSetOf()) { it.recordId }
        )
    }
}
