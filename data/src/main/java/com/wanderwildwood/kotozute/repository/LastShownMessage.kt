package com.wanderwildwood.kotozute.repository

import com.wanderwildwood.kotozute.model.EmojiReaction
import com.wanderwildwood.kotozute.model.Message
import io.realm.Realm
import io.realm.Sort

/**
 * The message a conversation is said to have ended on: its preview, its date, and whether it
 * is unread all come from this one.
 *
 * Someone else's reaction counts, as it does on an iPhone: it is news, so it moves the
 * conversation up and marks it unread, and the preview reads as they sent it ("Laughed at
 * “see you at 6”"). A reaction this phone sent does not -- nobody needs telling what they
 * just did -- and neither does a removal, or a reaction since replaced or taken back, since
 * none of those leaves anything under a message. A reaction's own record is what says it
 * still stands: [EmojiReaction.reactionMessageId] names the text that carried it.
 */
internal fun lastShownMessage(realm: Realm, threadId: Long): Message? =
    realm.where(Message::class.java)
        .equalTo("threadId", threadId)
        .sort("date", Sort.DESCENDING)
        .findAll()
        .firstOrNull { message ->
            !message.isEmojiReaction || (
                !message.isMe() &&
                    realm.where(EmojiReaction::class.java)
                        .equalTo("reactionMessageId", message.id)
                        .count() > 0
                )
        }
