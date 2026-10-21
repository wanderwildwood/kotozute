package com.wanderwildwood.kotozute.common.util

import java.util.Collections

/**
 * Conversations blocked from a screen opened over them, waiting for that conversation to
 * close.
 *
 * Blocking from a conversation's Info takes the chat out of the list, but the conversation
 * itself stayed open underneath, so nothing on screen said the block had worked. Now the
 * conversation leaves too, back to the list, where the chat is gone -- as the phone's own
 * Messages does. Info marks the conversation only after the block has gone through; the
 * conversation takes the mark when it comes back to the front and closes. Unblocking marks
 * nothing, so a blocked conversation that is opened and unblocked stays open.
 *
 * Keys are a Signal thread key as it is, or [sms] for a text conversation.
 */
object ClosedByBlock {

    private val waiting: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun sms(threadId: Long) = "sms:$threadId"

    fun mark(key: String) {
        waiting.add(key)
    }

    /** True once for a conversation that was blocked while it sat under another screen. */
    fun take(key: String): Boolean = waiting.remove(key)
}
