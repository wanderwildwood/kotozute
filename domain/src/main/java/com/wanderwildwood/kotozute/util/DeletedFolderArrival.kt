package com.wanderwildwood.kotozute.util

import com.wanderwildwood.kotozute.repository.ConversationRepository
import com.wanderwildwood.kotozute.repository.SignalRepository

/**
 * A text conversation in Deleted comes back whole when somebody writes into it: a text
 * arriving, or one sent from this phone. Whole means the Signal half too, where a link made by
 * hand joins the two into one row -- they went to Deleted together (`actOnPerson`), so they
 * come back together, or the row would come back holding half of what was said.
 */
object DeletedFolderArrival {

    fun putBack(conversationId: Long, conversations: ConversationRepository, signal: SignalRepository) {
        conversations.markUndeleted(listOf(conversationId))
        runCatching { signal.linkedThreadKeyFor(conversationId) }.getOrNull()
            ?.let { key -> signal.actOnPerson(key, SignalRepository.PersonAction.PUT_BACK) }
    }
}
