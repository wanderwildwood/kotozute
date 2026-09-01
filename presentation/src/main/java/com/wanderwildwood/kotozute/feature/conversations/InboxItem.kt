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
        override val stableId: Long
            get() = -2L - (thread.threadKey.hashCode().toLong() and 0xFFFFFFFFL)
    }

    companion object {
        fun isSignalId(id: Long): Boolean = id <= -2L
    }
}
