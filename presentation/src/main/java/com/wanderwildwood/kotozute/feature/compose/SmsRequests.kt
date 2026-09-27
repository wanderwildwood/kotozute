package com.wanderwildwood.kotozute.feature.compose

import com.wanderwildwood.kotozute.model.Conversation
import com.wanderwildwood.kotozute.model.Message
import com.wanderwildwood.kotozute.util.Preferences
import io.realm.Realm

/**
 * Message requests on the text side: a conversation somebody not in the address book started
 * and this phone has never written into. Held under Unknown with Accept, Delete and Block, the
 * way Signal holds one, until it is answered; SMS has no account to tell, so the answer is
 * kept on this phone.
 *
 * Every rule is one that can only make a request of a conversation nobody here has taken
 * part in: a contact, or anything sent, and it is not one.
 */
object SmsRequests {

    fun isRequest(prefs: Preferences, conversation: Conversation): Boolean {
        if (!conversation.isValid) return false
        val recipients = conversation.recipients
        if (recipients.isEmpty()) return false
        if (recipients.any { it.contact != null }) return false
        if (conversation.id.toString() in prefs.smsAcceptedRequests.get()) return false
        return Realm.getDefaultInstance().use { realm ->
            // Sent, in either table: anything that is not in the inbox (1) or "all" (0).
            realm.where(Message::class.java)
                .equalTo("threadId", conversation.id)
                .not().`in`("boxId", arrayOf<Int?>(0, 1))
                .count() == 0L
        }
    }

    fun accept(prefs: Preferences, threadId: Long) {
        prefs.smsAcceptedRequests.set(prefs.smsAcceptedRequests.get() + threadId.toString())
    }
}
