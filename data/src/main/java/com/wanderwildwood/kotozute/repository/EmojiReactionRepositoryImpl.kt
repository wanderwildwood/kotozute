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

import android.content.Context
import com.squareup.moshi.Moshi
import com.wanderwildwood.kotozute.extensions.insertOrUpdate
import com.wanderwildwood.kotozute.manager.KeyManager
import com.wanderwildwood.kotozute.model.Conversation
import com.wanderwildwood.kotozute.model.EmojiReaction
import com.wanderwildwood.kotozute.model.Message
import com.wanderwildwood.kotozute.util.EmojiPatternStrings
import io.realm.Realm
import io.realm.Sort
import timber.log.Timber
import javax.inject.Inject

class EmojiReactionRepositoryImpl @Inject constructor(
    private val context: Context,
    private val keyManager: KeyManager,
    private val moshi: Moshi,
) : EmojiReactionRepository {
    // We use an ordered map to make sure we can test tapback regexes before generic ones
    private val reactionPatterns: LinkedHashMap<Regex, (MatchResult) -> ParsedEmojiReaction?> = linkedMapOf(
        Regex( // Google Messages
            "(?s)^\u200a[^\u200b\u200a]*\u200b([^\u200b]*)\u200b[^\u200b\u200a]*\u200a(.*)\u200a[^\u200b\u200a]*\u200a\\Z"
        ) to { match -> ParsedEmojiReaction(match.groupValues[1], match.groupValues[2]) }
    )
    private val removalPatterns: LinkedHashMap<Regex, (MatchResult) -> ParsedEmojiReaction?> = linkedMapOf(
        Regex( // Google Messages
            "(?s)^\u200a[^\u200c\u200a]*\u200c([^\u200c]*)\u200c[^\u200c\u200a]*\u200a(.*)\u200a[^\u200c\u200a]*\u200a\\Z"
        ) to { match -> ParsedEmojiReaction(match.groupValues[1], match.groupValues[2], isRemoval = true) }
    )

    init {
        val assetEntries = loadEmojiPatternEntriesFromAssets()
        assetEntries.forEach { (localeTag, strings) ->
            try {
                addPatternsForLocaleStrings(localeTag, strings, reactionPatterns, removalPatterns)
            } catch (e: Exception) {
                Timber.w(e, "Failed to load asset patterns for locale: $localeTag")
            }
        }
        Timber.i("Loaded emoji reaction patterns for locales: ${assetEntries.map { it.first }}")
    }

    private fun addPatternsForLocaleStrings(
        localeTag: String,
        strings: EmojiPatternStrings,
        reactionPatterns: LinkedHashMap<Regex, (MatchResult) -> ParsedEmojiReaction?>,
        removalPatterns: LinkedHashMap<Regex, (MatchResult) -> ParsedEmojiReaction?>
    ) {
        // iOS tapbacks (important to add these before generic emoji patterns as the regexes may overlap)
        listOf(
            Triple("❤️", strings.iosHeartAdded, strings.iosHeartRemoved),
            Triple("👍", strings.iosLikeAdded, strings.iosLikeRemoved),
            Triple("👎", strings.iosDislikeAdded, strings.iosDislikeRemoved),
            Triple("😂", strings.iosLaughAdded, strings.iosLaughRemoved),
            Triple("‼️", strings.iosExclamationAdded, strings.iosExclamationRemoved),
            Triple("❓", strings.iosQuestionMarkAdded, strings.iosQuestionMarkRemoved)
        ).forEach { (emoji, added, removed) ->
            // A blank pattern matches every message; a translation missing one must add nothing.
            // (QUIK 7a467e92f)
            added?.takeIf { it.isNotBlank() }?.let {
                reactionPatterns[Regex(it)] =
                    { match -> ParsedEmojiReaction(emoji, match.groupValues[1]) }
            }
            removed?.takeIf { it.isNotBlank() }?.let {
                removalPatterns[Regex(it)] =
                    { match -> ParsedEmojiReaction(emoji, match.groupValues[1], isRemoval = true) }
            }
        }

        // Generic iOS emoji patterns
        strings.iosGenericAdded?.takeIf { it.isNotBlank() }?.let { pattern ->
            reactionPatterns[Regex(pattern)] = { match ->
                if (match.groupValues.getOrNull(1) == "with a sticker") null // TODO: localize "with a sticker"
                else ParsedEmojiReaction(match.groupValues[1], match.groupValues[2])
            }
        }
        strings.iosGenericRemoved?.takeIf { it.isNotBlank() }?.let { pattern ->
            removalPatterns[Regex(pattern)] = { match ->
                ParsedEmojiReaction(match.groupValues[1], match.groupValues[2], isRemoval = true)
            }
        }

        Timber.d("Loaded emoji regex patterns for $localeTag from assets")
    }

    private fun loadEmojiPatternEntriesFromAssets(): List<Pair<String, EmojiPatternStrings>> {
        val dir = "emojis"
        val files = context.assets.list(dir) ?: emptyArray()
        return files.filter { it.endsWith(".json", ignoreCase = true) }
            .mapNotNull { filename ->
                val localeTag = filename.removeSuffix(".json")
                try {
                    val json = context.assets.open("$dir/$filename").bufferedReader().use { it.readText() }
                    val data = parseEmojiPatternsJson(json)
                    localeTag to data
                } catch (e: Exception) {
                    Timber.w(e, "Failed parsing emoji patterns asset: $filename")
                    null
                }
            }
    }

    private fun parseEmojiPatternsJson(json: String): EmojiPatternStrings {
        val adapter = moshi.adapter(EmojiPatternStrings::class.java)
        return requireNotNull(adapter.fromJson(json)) { "Invalid emoji patterns JSON" }
    }

    override fun parseEmojiReaction(body: String): ParsedEmojiReaction? {
        parseAttachmentTapback(body)?.let { return it }

        val removal = parseRemoval(body)
        if (removal != null) return removal

        for ((pattern, parser) in reactionPatterns) {
            val match = pattern.find(body)
            if (match == null) continue;

            val result = parser(match)
            if (result == null) continue

            Timber.d("Reaction found with ${result.emoji}")
            return result
        }

        return null
    }

    override fun composeReaction(emoji: String, targetText: String, remove: Boolean): String =
        composeTapback(emoji, targetText, remove)

    override fun composeAttachmentReaction(emoji: String, partType: String, remove: Boolean): String =
        composeAttachmentTapback(emoji, partType, remove)

    /** Who a reaction is from: the other party's address, or [EmojiReactionRepository.ME]. */
    private fun senderOf(reactionMessage: Message): String =
        if (reactionMessage.isMe()) EmojiReactionRepository.ME else reactionMessage.address

    private fun parseRemoval(body: String): ParsedEmojiReaction? {
        for ((pattern, parser) in removalPatterns) {
            val match = pattern.find(body)
            if (match == null) continue;

            val result = parser(match)
            if (result == null) continue

            Timber.d("Removal found with ${result.emoji}")
            return result
        }

        return null
    }

    /**
     * Search for messages in the same thread with matching text content
     * We'll search recent messages first
     */
    override fun findTargetMessage(
        threadId: Long,
        reaction: ParsedEmojiReaction,
        before: Long,
        realm: Realm
    ): Message? {
        // A reaction to a picture quotes nothing, so it can only mean the newest picture in
        // the conversation when it was sent -- the same guess Google Messages makes.
        reaction.attachmentType?.let { type ->
            // Filtered here, not in the query: every MMS carries a SMIL part, so "any part
            // that is not text" would be true of all of them.
            return realm.where(Message::class.java)
                .equalTo("threadId", threadId)
                .equalTo("type", "mms")
                .lessThan("date", before)
                .sort("date", Sort.DESCENDING)
                .findAll()
                .firstOrNull { message ->
                    message.parts.any { part ->
                        if (type.isNotEmpty()) part.type.startsWith(type)
                        else part.type != "text/plain" && part.type != "application/smil"
                    }
                }
                .also { if (it == null) Timber.w("No earlier attachment for a reaction to one") }
        }
        val originalMessageText = reaction.originalMessage

        // From QUIK (41ac7ffe5, d23b35bfe): a target cannot be newer than its reaction, give
        // or take a minute for MMS arriving out of order; it is almost never 500 messages back;
        // and an iPhone shortens a long message it quotes, ending it with "…".
        val latestDate = before + MESSAGE_DATE_TOLERANCE_MS
        fun candidateQuery() = realm.where(Message::class.java)
            .equalTo("threadId", threadId)
            .lessThanOrEqualTo("date", latestDate)

        if (!originalMessageText.contains(MESSAGE_TRUNCATION_DELIMITER)) {
            candidateQuery()
                .equalTo("body", originalMessageText)
                .sort("date", Sort.DESCENDING)
                .findFirst()
                ?.let {
                    Timber.d("Found reaction target by exact body: message ID ${it.id}")
                    return it
                }
        }

        val startTime = System.currentTimeMillis()
        val candidates = candidateQuery()
            .sort("date", Sort.DESCENDING)
            .limit(MAX_TEXT_MATCH_CANDIDATES)
            .findAll()
        val originalMessageRegex = truncatedQuoteRegex(originalMessageText)
        val match = candidates.find { message ->
            originalMessageRegex.matches(message.getText(false).trim())
        }
        Timber.d("Scanned ${candidates.size} candidate emoji targets in ${System.currentTimeMillis() - startTime}ms")
        if (match != null) {
            Timber.d("Found match for reaction target: message ID ${match.id}")
            return match
        }

        // The text itself is somebody's message: not something for a log. (QUIK 6340ea506)
        Timber.w("No target message found for reaction text.")
        return null
    }

    private fun removeEmojiReaction(
        reactionMessage: Message,
        reaction: ParsedEmojiReaction,
        targetMessage: Message?,
        realm: Realm,
    ) {
        if (targetMessage == null) {
            Timber.w("Cannot remove emoji reaction '${reaction.emoji}': no target message found")
            return
        }

        val existingReaction = targetMessage.emojiReactions.find { candidate ->
            candidate.senderAddress == senderOf(reactionMessage) && candidate.emoji == reaction.emoji
        }

        if (existingReaction != null) {
            existingReaction.deleteFromRealm()
            Timber.d("Removed emoji reaction: ${reaction.emoji} to message ${targetMessage.id}")
        } else {
            Timber.w("No existing emoji reaction found to remove: ${reaction.emoji} to message ${targetMessage.id}")
        }

        reactionMessage.isEmojiReaction = true
        realm.insertOrUpdate(reactionMessage)
    }

    override fun saveEmojiReaction(
        reactionMessage: Message,
        parsedReaction: ParsedEmojiReaction,
        targetMessage: Message?,
        realm: Realm,
    ) {
        if (parsedReaction.isRemoval) {
            removeEmojiReaction(reactionMessage, parsedReaction, targetMessage, realm)
            return
        }

        val reaction = EmojiReaction().apply {
            id = keyManager.newId()
            reactionMessageId = reactionMessage.id
            senderAddress = senderOf(reactionMessage)
            emoji = parsedReaction.emoji
            originalMessageText = parsedReaction.originalMessage
            threadId = reactionMessage.threadId
        }
        realm.insertOrUpdate(reaction)

        if (targetMessage != null) {
            reactionMessage.isEmojiReaction = true
            realm.insertOrUpdate(reactionMessage)

            // Overwrite any previous reaction from this sender for this target
            val priorFromSender = targetMessage.emojiReactions.filter { it.senderAddress == reaction.senderAddress }
            priorFromSender.forEach { it.deleteFromRealm() }

            targetMessage.emojiReactions.add(reaction)

            Timber.i("Saved emoji reaction: ${reaction.emoji} to message ${targetMessage.id}")
        } else {
            Timber.w("No target message, cannot save emoji reaction: ${reaction.emoji}")
        }
    }

    override fun deleteAndReparseAllEmojiReactions(realm: Realm) {
        val startTime = System.currentTimeMillis()

        realm.delete(EmojiReaction::class.java)
        realm.where(Message::class.java).findAll().map {
            it.isEmojiReaction = false
        }

        val allMessages = realm.where(Message::class.java)
            .beginGroup()
                .beginGroup()
                    .equalTo("type", "sms")
                    .isNotEmpty("body")
                .endGroup()
                .or()
                .beginGroup()
                    .equalTo("type", "mms")
                    .notEqualTo("messageType", 130.toLong())
                    .isNotEmpty("parts.text")
                .endGroup()
            .endGroup()
            .sort("date", Sort.ASCENDING) // parse oldest to newest to handle reactions & removals properly
            .findAll()

        allMessages.forEach { message ->
            val text = message.getText(false)
            val parsedReaction = parseEmojiReaction(text)
            if (parsedReaction != null) {
                val targetMessage = findTargetMessage(
                    message.threadId,
                    parsedReaction,
                    message.date,
                    realm
                )
                saveEmojiReaction(
                    message,
                    parsedReaction,
                    targetMessage,
                    realm,
                )
            }
        }

        // A full sync picks each conversation's last message before this runs, when no
        // message is marked as a reaction yet -- so a thread whose latest text was our own
        // reaction, or a removal, would keep it as its preview. Re-pick those.
        realm.where(Conversation::class.java)
            .equalTo("lastMessage.isEmojiReaction", true)
            .findAll()
            .forEach { conversation ->
                conversation.lastMessage = lastShownMessage(realm, conversation.id)
            }

        val endTime = System.currentTimeMillis()
        Timber.d("Deleted and reparsed all emoji reactions in ${endTime - startTime}ms")
    }

}

/**
 * The text of an SMS reaction, as an iPhone writes it.
 *
 * English whatever the phone's language, because this is a format and not a sentence: the
 * other phone matches it against the phrases it knows, and English is the one every parser
 * knows -- ours included (`assets/emojis/en.json`), so the sent message is recognised here
 * too and drawn as a reaction rather than as a text.
 */
private const val MAX_TEXT_MATCH_CANDIDATES = 500L
private const val MESSAGE_DATE_TOLERANCE_MS = 60_000L
private const val MESSAGE_TRUNCATION_DELIMITER = "\u2026"

/**
 * What a quoted reaction's text matches. An iPhone quoting a long message cuts it short and
 * ends it with "…", so everything before the last "…" is a prefix of the target. (QUIK
 * d23b35bfe)
 */
internal fun truncatedQuoteRegex(originalMessageText: String): Regex {
    val reactionText = originalMessageText.trim()
    val index = reactionText.lastIndexOf(MESSAGE_TRUNCATION_DELIMITER)
    val pattern = if (index == -1) Regex.escape(reactionText)
    else Regex.escape(reactionText.take(index)) + ".*"
    return Regex("^$pattern$", RegexOption.DOT_MATCHES_ALL)
}

/**
 * An iPhone's reaction to a picture: `Loved an image`, `Removed a like from a movie`. It
 * quotes nothing -- there is no text to quote -- so the quoted patterns never see it, and
 * without this it arrives as a message of its own reading "Loved an image".
 *
 * English only, like the quoted form [composeTapback] writes: these are the phrases every
 * phone that turns them back into reactions matches.
 */
internal fun parseAttachmentTapback(body: String): ParsedEmojiReaction? {
    val match = ATTACHMENT_TAPBACK.matchEntire(body.trim()) ?: return null
    val (phrase, generic, what) = match.destructured
    val type = when (what) {
        "an image" -> "image/"
        "a movie" -> "video/"
        else -> ""
    }
    val removal = phrase.startsWith("Removed")
    val emoji = generic.ifEmpty {
        TAPBACK_PHRASES.entries.firstOrNull { (_, p) -> p.first == phrase || p.second == phrase }?.key
            ?: return null
    }
    return ParsedEmojiReaction(emoji, "", isRemoval = removal, attachmentType = type)
}

private val TAPBACK_PHRASES = linkedMapOf(
    "❤️" to ("Loved" to "Removed a heart from"),
    "👍" to ("Liked" to "Removed a like from"),
    "👎" to ("Disliked" to "Removed a dislike from"),
    "😂" to ("Laughed at" to "Removed a laugh from"),
    "‼️" to ("Emphasized" to "Removed an exclamation from"),
    "❓" to ("Questioned" to "Removed a question mark from"),
)

private val ATTACHMENT_TAPBACK = Regex(
    "^(" + TAPBACK_PHRASES.values.flatMap { listOf(it.first, it.second) }.joinToString("|") { Regex.escape(it) } +
        "|Reacted|Removed)(?: (\\S+?) (?:to|from))? (an image|a movie|an attachment)$"
)

internal fun composeTapback(emoji: String, targetText: String, remove: Boolean): String =
    "${tapbackPhrase(emoji, remove)} “${targetText.trim()}”"

/**
 * The text of an SMS reaction to a picture, video or other attachment with no text of its
 * own: `Loved an image`. What [parseAttachmentTapback] reads, and what an iPhone sends.
 */
internal fun composeAttachmentTapback(emoji: String, partType: String, remove: Boolean): String {
    val what = when {
        partType.startsWith("image/") -> "an image"
        partType.startsWith("video/") -> "a movie"
        else -> "an attachment"
    }
    return "${tapbackPhrase(emoji, remove)} $what"
}

private fun tapbackPhrase(emoji: String, remove: Boolean): String =
    TAPBACK_PHRASES[emoji]?.let { if (remove) it.second else it.first }
        ?: if (remove) "Removed $emoji from" else "Reacted $emoji to"
