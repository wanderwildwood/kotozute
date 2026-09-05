package com.wanderwildwood.kotozute.feature.conversations

import com.wanderwildwood.kotozute.model.Conversation
import com.wanderwildwood.kotozute.model.SignalThread

/**
 * One row of the inbox, from either rail.
 *
 * The two rails cannot share a Realm class -- a full sync deletes every Conversation
 * row -- so they are merged here, at the list, rather than in the database.
 */
sealed class InboxItem {

    abstract val sortDate: Long

    /** Pinned threads sit above the rest, on either rail. */
    abstract val pinned: Boolean

    /** Stable id for the adapter and for swipe. */
    abstract val stableId: Long

    /**
     * False once the row behind this item has been deleted.
     *
     * The list holds live Realm objects, so a deletion invalidates the item it is holding
     * before the rebuilt list reaches the adapter. Reading any field of an invalidated
     * object throws, and the read that happens first is RecyclerView's own, during the
     * layout pass the deletion triggers. Every accessor below has to ask this first.
     */
    abstract val isValid: Boolean

    data class Sms(val conversation: Conversation) : InboxItem() {
        override val isValid: Boolean get() = conversation.isValid
        override val sortDate: Long get() = if (isValid) conversation.date else 0
        override val pinned: Boolean get() = isValid && conversation.pinned
        // Read once, here, while the row is certainly alive: two rows deleted together
        // would otherwise both answer with the same fallback, and RecyclerView refuses
        // to hold two view holders under one stable id.
        override val stableId: Long = conversation.id
    }

    data class Signal(val thread: SignalThread) : InboxItem() {
        override val isValid: Boolean get() = thread.isValid
        override val sortDate: Long get() = if (isValid) thread.lastTs else 0
        override val pinned: Boolean get() = isValid && thread.pinned

        /**
         * Negative, so it can never collide with a telephony thread id (always positive)
         * and never equals the -1 the adapter returns for "no item". The sign is also how
         * the swipe callback recognises a Signal row without reaching for the item.
         */
        override val stableId: Long = signalStableId(thread.threadKey)
    }

    companion object {
        /**
         * The id a Signal thread answers to wherever a Long is required -- the inbox
         * adapter, the swipe callback, and the desktop relay's URLs. Negative, so it can
         * never collide with a telephony thread id, and never -1, which means "no item".
         *
         * Defined once because two places derive it and a disagreement between them would
         * send a reply to the wrong conversation.
         */
        fun signalStableId(threadKey: String): Long =
            -2L - (threadKey.hashCode().toLong() and 0xFFFFFFFFL)

        fun isSignalId(id: Long): Boolean = id <= -2L
    }
}
