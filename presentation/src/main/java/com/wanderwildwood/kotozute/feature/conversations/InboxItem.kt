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

    /** Stable id for the adapter and for swipe. */
    abstract val stableId: Long

    data class Sms(val conversation: Conversation) : InboxItem() {
        override val sortDate: Long get() = conversation.date
        override val stableId: Long get() = conversation.id
    }

    data class Signal(val thread: SignalThread) : InboxItem() {
        override val sortDate: Long get() = thread.lastTs

        /**
         * Negative, so it can never collide with a telephony thread id (always positive)
         * and never equals the -1 the adapter returns for "no item". The sign is also how
         * the swipe callback recognises a Signal row without reaching for the item.
         */
        override val stableId: Long get() = signalStableId(thread.threadKey)
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
