package com.wanderwildwood.kotozute.feature.main

import com.wanderwildwood.kotozute.model.SearchResult
import com.wanderwildwood.kotozute.model.SignalThread

/**
 * One row of search results, from either rail.
 *
 * Search only ever looked at SMS: SearchResult wraps a Conversation, which is a telephony
 * type, and there was no shape a Signal thread could take. With the two rails woven into
 * one list -- the default -- that meant searching an inbox and being shown half of it, with
 * nothing to say the other half had not been looked at.
 *
 * SearchResult stays as it is. It lives in the domain module, and SignalThread is not
 * something it should learn about; the merge happens here, where both are already known.
 */
sealed class InboxSearchResult {

    /** How many messages matched inside the thread; 0 means the thread itself matched. */
    abstract val messages: Int

    data class Sms(val result: SearchResult) : InboxSearchResult() {
        override val messages: Int get() = result.messages
    }

    data class Signal(
        val thread: SignalThread,
        override val messages: Int,
        /** The matching message, so a hit inside a conversation can show what it found. */
        val snippet: String
    ) : InboxSearchResult()
}
