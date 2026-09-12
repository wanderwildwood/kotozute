package com.wanderwildwood.kotozute.signalstore

import com.wanderwildwood.kotozute.signal.BridgeMessage
import org.json.JSONArray
import org.json.JSONObject
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.DataMessage

/**
 * Turns a decrypted [Content] into the [BridgeMessage] the app already knows how to file.
 *
 * A port of the bridge's `normalize.go`, and deliberately a *faithful* one. The whole Realm
 * rail -- threads, previews, reactions, expiry, the merged inbox -- already consumes
 * BridgeMessage, and every rule below was arrived at by something going wrong once. Deriving
 * them again from the protobuf would be re-litigating those bugs, and worse, any drift in the
 * thread key would file messages into *new* threads beside the existing ones rather than into
 * them.
 *
 * (The name `BridgeMessage` is now a misnomer -- nothing here goes near a bridge. Renaming it
 * touches the whole rail and belongs in its own change, not this one.)
 *
 * The subtlety that costs a broken thread if missed: a message the user sent from another
 * device arrives as `syncMessage.sent` with the **recipient** in the destination, while
 * everyone else's arrives as `dataMessage` with the **sender** in the envelope source. Both
 * are real messages in the same thread, and they identify the counterpart from opposite ends.
 */
internal object ContentNormalizer {

    /**
     * Exposed for the self-check. The derivation is the part worth pinning: it is a pure
     * function of the master key, and getting it wrong produces a stable, plausible, wrong
     * thread key rather than an error.
     */
    internal fun groupIdForCheck(masterKey: ByteArray): String =
        groupIdOf(DataMessage(groupV2 = org.whispersystems.signalservice.internal.push.GroupContextV2(
            masterKey = okio.ByteString.of(*masterKey)
        )))


    /**
     * @return the message to store, or null for traffic that is not one -- typing indicators,
     *   config sync, receipts, and expiration-timer changes.
     */
    fun normalize(
        content: Content,
        metadata: EnvelopeMetadata,
        selfAci: String?,
        selfE164: String?,
        /**
         * What to call somebody a message mentions. See [withMentions] -- without it a mention
         * is a placeholder character and nothing else.
         */
        nameFor: (String) -> String? = { null }
    ): BridgeMessage? {
        var authorUuid = metadata.sourceServiceId.toString()
        var authorNumber = metadata.sourceE164.orEmpty()

        val dataMessage: DataMessage
        var outgoing: Boolean
        var counterpartUuid: String
        var counterpartNumber: String

        val sent = content.syncMessage?.sent

        // When a message is an edit, the timestamp that identifies it is the *original's*.
        // That is what makes an edit land on the message it edits: the row's id is author and
        // timestamp, so reusing the original's timestamp updates that row in place instead of
        // adding a second bubble, and keeps it where it already sits in the thread rather than
        // jumping it to the end.
        var editTarget: Long? = null

        when {
            sent?.message != null -> {
                dataMessage = sent.message!!
                outgoing = true
                // The author comes from the envelope rather than from selfAci, so this stays
                // correct even before self has been worked out.
                if (authorUuid.isBlank()) authorUuid = selfAci.orEmpty()
                counterpartUuid = destinationServiceIdOf(sent)
                counterpartNumber = sent.destinationE164.orEmpty()
            }
            content.dataMessage != null -> {
                dataMessage = content.dataMessage!!
                outgoing = false
                counterpartUuid = authorUuid
                counterpartNumber = authorNumber
            }
            // An edit of something we sent, synced from the device that made it.
            sent?.editMessage?.dataMessage != null -> {
                dataMessage = sent.editMessage!!.dataMessage!!
                outgoing = true
                editTarget = sent.editMessage!!.targetSentTimestamp
                if (authorUuid.isBlank()) authorUuid = selfAci.orEmpty()
                counterpartUuid = destinationServiceIdOf(sent)
                counterpartNumber = sent.destinationE164.orEmpty()
            }
            // Somebody editing what they sent us.
            content.editMessage?.dataMessage != null -> {
                dataMessage = content.editMessage!!.dataMessage!!
                outgoing = false
                editTarget = content.editMessage!!.targetSentTimestamp
                counterpartUuid = authorUuid
                counterpartNumber = authorNumber
            }
            // Receipts, typing, call signalling, sender-key distribution: real traffic, but
            // nothing that belongs in a thread as a message.
            else -> return null
        }

        val groupId = groupIdOf(dataMessage)

        val threadKey = threadKeyFor(
            outgoing = outgoing,
            counterpartUuid = counterpartUuid,
            counterpartNumber = counterpartNumber,
            groupId = groupId,
            selfAci = selfAci,
            selfE164 = selfE164
        ) ?: return null

        // A timer change carries no message. Signal shows it as an event in the thread; kept
        // as a message it would be an empty bubble, and kept as one that never expires it
        // would be a permanent record of a conversation being made impermanent.
        //
        // ⚠ Told apart by the flag Signal sets for exactly this, not by guessing. The guess
        // this replaces -- "has an expireTimerVersion and no body, attachments or reaction" --
        // was not a test for a timer change at all: a modern Signal sets expireTimerVersion on
        // **every** message it sends, so the condition reduced to "has no body, attachments or
        // reaction", and every sticker-only message, shared contact and quote-only reply was
        // silently discarded before it could be stored. It also made the sticker fallback
        // below unreachable for any current sender.
        if (isExpirationUpdate(dataMessage)) return null

        // ⚠ An edit is a rewrite of a row this app already holds, and the row it rewrites is
        // chosen by author and timestamp alone -- so an edit carrying a *different* group
        // context would move somebody's message into another conversation. Signal resolves an
        // edit against the original's thread; here the honest equivalent is to refuse one that
        // disagrees, because the original's thread is not known at this layer.
        if (editTarget != null && groupId.isNotBlank() && content.dataMessage?.groupV2 == null &&
            content.syncMessage?.sent?.editMessage?.dataMessage?.groupV2 == null
        ) {
            return null
        }

        // The original's timestamp for an edit, its own for anything else. An edit naming no
        // target is not an edit of anything and there is nothing to apply it to.
        val timestamp = editTarget ?: dataMessage.timestamp ?: 0L
        if (timestamp == 0L) return null

        // A dataMessage whose author is our own account is Note to Self -- our own group and
        // direct sends come back as a sync instead, so this is the only case. The user wrote
        // it, so it belongs on the outgoing side.
        if (!outgoing && !selfAci.isNullOrBlank() && authorUuid == selfAci) outgoing = true

        val reaction = dataMessage.reaction
        val viewOnce = dataMessage.isViewOnce == true

        var body = dataMessage.body.orEmpty()
        // A sticker with no caption. The image lives in a pack this app cannot draw, but a
        // bubble saying which one it was is the truth and a hole in the thread is not. The
        // emoji the pack assigns is what Signal falls back to in its own notifications.
        if (body.isEmpty() && dataMessage.sticker != null) {
            body = dataMessage.sticker?.emoji?.takeIf { it.isNotBlank() } ?: "(sticker)"
        }
        body = withMentions(body, dataMessage.bodyRanges, nameFor)

        // Not a bubble in anybody's client. A vote belongs to its poll and a pin belongs to
        // the message it pins; both were being stored as a row with nothing in it, which is a
        // blank line in the conversation where nothing happened.
        if (isNotAMessage(dataMessage)) return null

        // Something this app cannot draw. Signal always shows *something* -- the whole point
        // of a conversation is that it accounts for itself -- and an empty bubble is this
        // app's own invention, arrived at by having no case for the message rather than by
        // deciding anything. The sticker line above is the same idea and was already here.
        if (body.isEmpty() && dataMessage.attachments.isEmpty() && reaction == null) {
            describe(dataMessage)?.let { body = it }
        }

        return BridgeMessage(
            id = messageIdFor(authorUuid, authorNumber, timestamp),
            seq = 0,
            threadKey = threadKey,
            ts = timestamp,
            senderUuid = authorUuid,
            senderNumber = authorNumber,
            outgoing = outgoing,
            body = body,
            groupId = groupId,
            quoteTs = dataMessage.quote?.id ?: 0L,
            // Our own messages are not unread.
            read = outgoing,
            source = "live",
            attachmentsJson = attachmentsJson(dataMessage, viewOnce),
            // When this message's time runs out, or 0 for "the clock has not started".
            //
            // ⚠ An incoming message does not start counting until it is read. It used to start
            // at the moment it was decrypted, on the reasoning that erring early was safe --
            // it is not. A thirty-second message that arrives while the phone is in a pocket
            // was deleted before its reader ever saw it: a real message, gone, with no trace
            // and nothing to say it had been there. Signal keeps the deadline unstarted until
            // the message is actually read, and markRead sets it here.
            //
            // Our own sent message is different: it has been seen by definition, and the
            // primary tells us when its clock began. That timestamp is the primary's, not this
            // device's, so a linked phone that was asleep does not restart the countdown.
            expiresAt = (dataMessage.expireTimer ?: 0).takeIf { it > 0 }?.let { seconds ->
                when {
                    !outgoing -> 0L
                    sent?.expirationStartTimestamp?.takeIf { it > 0 } != null ->
                        sent.expirationStartTimestamp!! + seconds * 1000L
                    else -> System.currentTimeMillis() + seconds * 1000L
                }
            } ?: 0L,
            expiresInSeconds = (dataMessage.expireTimer ?: 0).toLong(),
            viewOnce = viewOnce,
            reactionEmoji = reaction?.emoji.orEmpty(),
            reactionTarget = reaction?.let { r ->
                // Both fields, as everywhere else. Reading only the binary twin loses every
                // reaction from a client that fills the string one -- Desktop, iOS, anything
                // signal-cli-shaped -- and it loses them silently: the target resolves to
                // ":<timestamp>", matches no row, and the reaction is simply never shown.
                val target = org.signal.core.models.ServiceId
                    .parseOrNull(r.targetAuthorAci, r.targetAuthorAciBinary)
                    ?.toString()
                    .orEmpty()
                "$target:${r.targetSentTimestamp ?: 0}"
            }.orEmpty(),
            reactionRemove = reaction?.remove == true,
            groupMasterKey = dataMessage.groupV2?.masterKey?.toByteArray()
        )
    }

    /**
     * Which conversation a message belongs to.
     *
     * Extracted from the protobuf handling because every rule in it was arrived at by
     * something going wrong once, and because none of them need a protobuf, a network or a
     * native library to state -- so they can be tested, which the rest of this file cannot be
     * (deriving a group id calls into zkgroup).
     *
     * @return the thread key, or null when there is nothing to hang a thread on.
     */
    /** A conversation's disappearing-messages timer, as one message changed it. */
    data class TimerUpdate(val threadKey: String, val seconds: Long, val version: Int)

    /**
     * The timer change in this content, if it is one.
     *
     * Extracted rather than folded into [normalize] because a timer change is not a message
     * and [normalize] answers only with messages -- but the change still has to reach the
     * conversation, which is the whole of what was missing: the timer was detected, discarded,
     * and never stored anywhere, so every reply this phone sent carried no timer at all.
     */
    fun timerUpdateIn(
        content: Content,
        metadata: EnvelopeMetadata,
        selfAci: String?,
        selfE164: String?
    ): TimerUpdate? {
        val sent = content.syncMessage?.sent
        val dataMessage = sent?.message ?: content.dataMessage ?: return null
        if (!isExpirationUpdate(dataMessage)) return null

        val outgoing = sent?.message != null
        val counterpartUuid = if (outgoing) destinationServiceIdOf(sent!!) else metadata.sourceServiceId.toString()
        val counterpartNumber = if (outgoing) sent!!.destinationE164.orEmpty() else metadata.sourceE164.orEmpty()
        val threadKey = threadKeyFor(
            outgoing = outgoing,
            counterpartUuid = counterpartUuid,
            counterpartNumber = counterpartNumber,
            groupId = groupIdOf(dataMessage),
            selfAci = selfAci,
            selfE164 = selfE164
        ) ?: return null

        return TimerUpdate(
            threadKey = threadKey,
            seconds = (dataMessage.expireTimer ?: 0).toLong(),
            version = dataMessage.expireTimerVersion ?: 0
        )
    }

    /**
     * Whether this message is a change to the conversation's disappearing-messages timer.
     *
     * The sender sets `EXPIRATION_TIMER_UPDATE` and only that flag when the timer changes;
     * everything else is a message, however little it carries. Signal tests the same flag in
     * the same place (`SignalServiceProtoUtil.isExpirationUpdate`).
     */
    internal fun isExpirationUpdate(dataMessage: DataMessage): Boolean {
        val flags = dataMessage.flags ?: return false
        return flags and DataMessage.Flags.EXPIRATION_TIMER_UPDATE.value != 0
    }

    /**
     * Who a message we sent from another device was sent *to*.
     *
     * Three places carry it and a client may populate any of them. The string field is the
     * old one; newer clients send the same value as raw bytes in `destinationServiceIdBinary`
     * and leave the string null. Reading only the string made a modern primary's transcript
     * look like it named nobody, so the thread key fell through to the destination *number* --
     * and a number is not a service id, so that thread could be read and never replied to.
     * Seen on a real account: one conversation split in two, the other person's messages
     * under their service id and our own side of it under their phone number, the second
     * thread refusing every send with "not a service id".
     *
     * The per-recipient delivery statuses are the last resort. A one-to-one send has exactly
     * one, naming the same person, and it survives in transcripts where both destination
     * fields are empty.
     */
    internal fun destinationServiceIdOf(sent: org.whispersystems.signalservice.internal.push.SyncMessage.Sent): String {
        fun parse(text: String?, binary: okio.ByteString?): String? {
            text?.takeIf { it.isNotBlank() }
                ?.let { org.signal.core.models.ServiceId.parseOrNull(it) }
                ?.let { return it.toString() }
            binary?.takeIf { it.size > 0 }
                ?.let { org.signal.core.models.ServiceId.parseOrNull(it) }
                ?.let { return it.toString() }
            return null
        }

        parse(sent.destinationServiceId, sent.destinationServiceIdBinary)?.let { return it }
        sent.unidentifiedStatus.forEach { status ->
            parse(status.destinationServiceId, status.destinationServiceIdBinary)?.let { return it }
        }
        return ""
    }

    /**
     * Messages that are instructions rather than things anybody said.
     *
     * Signal folds each of these into the thing it refers to. Since this app draws neither
     * polls nor pinned messages, the honest answer is to file nothing at all rather than an
     * empty row -- a reader can act on neither, and only one of them looks like a fault.
     */
    private fun isNotAMessage(m: DataMessage): Boolean =
        m.pollVote != null || m.pinMessage != null || m.unpinMessage != null

    /**
     * What to call a message this build cannot render.
     *
     * Every one of these is a real message somebody sent, and every one of them arrived here
     * as an empty bubble: no text, no attachment, nothing to say why. A short description is
     * the truth and is what Signal shows in its own notifications for the same content.
     *
     * The protocol-version check goes last, because it is the general case: a message from a
     * newer client using a feature that did not exist when this was built has no field here
     * to recognise, and saying so beats a blank.
     */
    private fun describe(m: DataMessage): String? = when {
        m.pollCreate != null ->
            m.pollCreate?.question?.takeIf { it.isNotBlank() }?.let { "(poll) $it" } ?: "(a poll)"
        m.pollTerminate != null -> "(a poll ended)"
        m.contact.isNotEmpty() -> "(a contact card)"
        m.groupCallUpdate != null -> "(a call)"
        m.payment != null -> "(a payment)"
        m.giftBadge != null -> "(a gift)"
        m.adminDelete != null -> null
        (m.requiredProtocolVersion ?: 0) > DataMessage.ProtocolVersion.CURRENT.value ->
            "(a message this version of the app cannot show)"
        else -> null
    }

    /**
     * Puts the names back into a message that mentions people.
     *
     * ⚠ A mention is **not** in the text. Signal puts one `U+FFFC` object-replacement
     * character in the body per mention and names the person in a parallel `bodyRanges` entry.
     * A client that ignores those ranges therefore renders a message reading "\uFFFC did you
     * see this" -- a stray box where a name should be -- and every group message that mentions
     * anybody is corrupted in exactly that way. Nothing about it looks like a fault in the
     * code; it looks like the sender typed a strange character.
     *
     * Substituted here, on the way in, rather than at display time as Signal does it. Signal
     * keeps mentions in their own table and re-resolves them on every draw, so a contact
     * renamed later updates old messages; this stores what was known when the message arrived.
     * That is the honest trade for not having a mentions table: a name that was right at the
     * time, rather than a placeholder for ever.
     *
     * The ranges are applied back to front so that each start index still refers to the string
     * being edited -- replacing left to right moves every later index along by the difference.
     */
    private fun withMentions(
        body: String,
        ranges: List<org.whispersystems.signalservice.internal.push.BodyRange>,
        nameFor: (String) -> String?
    ): String {
        if (body.isEmpty() || ranges.isEmpty()) return body
        val mentions = ranges
            .mapNotNull { range ->
                // Both fields, as everywhere else on this rail: a modern client fills only the
                // binary one, and reading the string alone drops every mention it sends.
                val aci = org.signal.core.models.ServiceId
                    .parseOrNull(range.mentionAci, range.mentionAciBinary)
                    ?.toString()
                    ?: return@mapNotNull null
                val start = range.start ?: return@mapNotNull null
                val length = range.length ?: return@mapNotNull null
                if (start < 0 || length <= 0 || start + length > body.length) return@mapNotNull null
                Triple(start, length, aci)
            }
            .sortedByDescending { it.first }
        if (mentions.isEmpty()) return body

        val out = StringBuilder(body)
        mentions.forEach { (start, length, aci) ->
            // A name if anybody has one, the service id shortened if nobody does. Never the
            // placeholder, and never the whole id: this goes inline in a sentence.
            val name = nameFor(aci)?.takeIf { it.isNotBlank() }
                ?: aci.take(com.wanderwildwood.kotozute.signal.SignalName.SHORT_SERVICE_ID)
            out.replace(start, start + length, "@$name")
        }
        return out.toString()
    }

    internal fun threadKeyFor(
        outgoing: Boolean,
        counterpartUuid: String,
        counterpartNumber: String,
        groupId: String,
        selfAci: String?,
        selfE164: String?
    ): String? {
        if (groupId.isNotBlank()) return "group:$groupId"

        // Note to Self, by whichever identifier the envelope happens to carry.
        //
        // The bridge only had to handle the destination being absent entirely, because that
        // is what signal-cli reports. The raw protobuf is not so tidy: a note to self arrives
        // with the destination **populated with our own number**, so testing for absence
        // alone filed it under `direct:+1555...` while the bridge filed the same conversation
        // under `direct:<aci>` -- two threads for one conversation. Measured, not
        // hypothetical.
        //
        // **Both** halves must be checked. Testing the uuid alone filed a message sent to a
        // real person as Note to Self whenever only their number was known -- which happens
        // for a recipient not yet resolved to an ACI -- and a reply in that thread then went
        // to yourself.
        val isSelf = (counterpartUuid.isNotBlank() && counterpartUuid == selfAci) ||
            (counterpartNumber.isNotBlank() && counterpartNumber == selfE164)
        val isAbsent = counterpartUuid.isBlank() && counterpartNumber.isBlank()
        if (outgoing && (isAbsent || isSelf)) {
            val self = selfAci.orEmpty().ifBlank { counterpartUuid }
            return if (self.isBlank()) null else "direct:$self"
        }

        val id = counterpartUuid.ifBlank { counterpartNumber }
        return if (id.isBlank()) null else "direct:$id"
    }

    /**
     * The identity of a message: its author and its timestamp.
     *
     * Stable across the several notifications one message produces, and across a re-import --
     * which is what keeps a redelivered envelope from becoming a second copy in the thread.
     */
    internal fun messageIdFor(authorUuid: String, authorNumber: String, timestamp: Long): String =
        "${authorUuid.ifBlank { authorNumber }}:$timestamp"

    /**
     * The group's id, derived the way signal-cli derives it.
     *
     * **Not the master key.** The wire carries a master key; the id is what you get by
     * deriving secret params from it and taking the public group identifier. Base64 of the
     * master key would be a perfectly stable, perfectly wrong thread key -- the bridge files
     * the same group under the derived id, so the two rails would split every group
     * conversation into two threads, exactly as they briefly did for Note to Self.
     *
     * Standard base64, not URL-safe and not unpadded, because that is what signal-cli's
     * `GroupId.toBase64()` produces and the thread key has to match it character for
     * character.
     */
    private fun groupIdOf(dataMessage: DataMessage): String {
        val masterKeyBytes = dataMessage.groupV2?.masterKey?.toByteArray() ?: return ""
        return runCatching {
            val masterKey = org.signal.libsignal.zkgroup.groups.GroupMasterKey(masterKeyBytes)
            val identifier = org.signal.libsignal.zkgroup.groups.GroupSecretParams
                .deriveFromMasterKey(masterKey)
                .publicParams
                .groupIdentifier
                .serialize()
            android.util.Base64.encodeToString(identifier, android.util.Base64.NO_WRAP)
        }.getOrElse {
            // A group whose id cannot be derived has nothing to hang a thread on, and
            // guessing one would file the message in a thread nothing else will ever match.
            ""
        }
    }

    /**
     * Attachment *metadata* only, and only as a floor.
     *
     * ⚠ Not the last word on an attachment. `SignalReceiver.withAttachments` runs after this
     * and replaces what is written here with the real download, setting `pending` to whether
     * the fetch actually failed. This stands for the paths that do not go through it -- an
     * import reading bytes it already holds, and a message whose attachments were skipped --
     * so the row can still say a picture exists rather than silently losing it.
     *
     * (This comment used to say nothing downloaded attachments at all, which stopped being
     * true and then misled a reading of this file.)
     *
     * A view-once attachment is not recorded at all. Signal's promise is that it can be opened
     * once, and writing its id into a column anything can read is not that.
     */
    private fun attachmentsJson(dataMessage: DataMessage, viewOnce: Boolean): String {
        if (viewOnce || dataMessage.attachments.isEmpty()) return ""
        val array = JSONArray()
        dataMessage.attachments.forEach { pointer ->
            array.put(
                JSONObject()
                    .put("id", pointer.cdnKey.orEmpty())
                    .put("type", pointer.contentType.orEmpty())
                    .put("filename", pointer.fileName.orEmpty())
                    .put("size", pointer.size ?: 0)
                    // Not fetched. The UI can draw a row that says so rather than pretending.
                    .put("pending", true)
            )
        }
        return array.toString()
    }
}

/** A binary service id as the text form the rest of the app stores. */
private object ServiceIdText {
    fun of(bytes: okio.ByteString): String =
        org.signal.core.models.ServiceId.parseOrNull(bytes.toByteArray())?.toString().orEmpty()
}
