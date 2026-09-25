/*
 * Copyright (C) 2025
 *
 * This file is part of QUIK.
 *
 * QUIK is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QUIK is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QUIK.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.wanderwildwood.kotozute.repository

import com.wanderwildwood.kotozute.model.Message
import io.realm.Realm

data class ParsedEmojiReaction(val emoji: String, val originalMessage: String, val isRemoval: Boolean = false)

interface EmojiReactionRepository {
    companion object {
        /**
         * The sender recorded for a reaction this phone sent. A sent message's address is the
         * person it went to, so recording that would file our reaction under theirs -- and
         * the one-reaction-per-sender rule would then have each replace the other.
         */
        const val ME = "me"

        /**
         * The six an SMS reaction offers: the iPhone's own tapbacks. Each has a fixed English
         * phrase ("Loved", "Laughed at") that iPhones and Google Messages both turn back into a
         * reaction, where any other emoji only reads as one on newer phones.
         */
        val SMS_CHOICES = listOf("\u2764\ufe0f", "\uD83D\uDC4D", "\uD83D\uDC4E", "\uD83D\uDE02", "\u203c\ufe0f", "\u2753")
    }

    fun parseEmojiReaction(body: String): ParsedEmojiReaction?

    /**
     * The text message that carries a reaction to [targetText], in the form the other phone
     * reads as one: `Loved “see you at 6”`, or `Removed a heart from “see you at 6”`.
     */
    fun composeReaction(emoji: String, targetText: String, remove: Boolean): String

    fun findTargetMessage(threadId: Long, originalMessageText: String, realm: Realm): Message?

    fun saveEmojiReaction(
        reactionMessage: Message,
        parsedReaction: ParsedEmojiReaction,
        targetMessage: Message?,
        realm: Realm,
    )

    fun deleteAndReparseAllEmojiReactions(realm: Realm)
}
