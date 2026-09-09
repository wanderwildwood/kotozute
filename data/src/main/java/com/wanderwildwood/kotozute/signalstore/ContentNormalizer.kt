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
        selfE164: String?
    ): BridgeMessage? {
        var authorUuid = metadata.sourceServiceId.toString()
        var authorNumber = metadata.sourceE164.orEmpty()

        val dataMessage: DataMessage
        var outgoing: Boolean
        var counterpartUuid: String
        var counterpartNumber: String

        val sent = content.syncMessage?.sent
        when {
            sent?.message != null -> {
                dataMessage = sent.message!!
                outgoing = true
                // The author comes from the envelope rather than from selfAci, so this stays
                // correct even before self has been worked out.
                if (authorUuid.isBlank()) authorUuid = selfAci.orEmpty()
                counterpartUuid = sent.destinationServiceId.orEmpty()
                counterpartNumber = sent.destinationE164.orEmpty()
            }
            content.dataMessage != null -> {
                dataMessage = content.dataMessage!!
                outgoing = false
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
        if (dataMessage.expireTimerVersion != null && dataMessage.body.isNullOrEmpty() &&
            dataMessage.attachments.isEmpty() && dataMessage.reaction == null
        ) {
            return null
        }

        val timestamp = dataMessage.timestamp ?: 0L
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
            // The clock starts now rather than at the moment of reading: a read on another
            // device is not observable here, and erring early is the safe direction.
            expiresAt = (dataMessage.expireTimer ?: 0).takeIf { it > 0 }
                ?.let { System.currentTimeMillis() + it * 1000L } ?: 0L,
            expiresInSeconds = (dataMessage.expireTimer ?: 0).toLong(),
            viewOnce = viewOnce,
            reactionEmoji = reaction?.emoji.orEmpty(),
            reactionTarget = reaction?.let { r ->
                "${r.targetAuthorAciBinary?.let { ServiceIdText.of(it) } ?: ""}:${r.targetSentTimestamp ?: 0}"
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
     * Attachment *metadata* only.
     *
     * The bridge sent descriptions of files it had already fetched; here there is only a
     * pointer, and nothing downloads it yet. Recorded rather than dropped so the row can say
     * an attachment exists -- a message that silently loses its picture is worse than one that
     * says it has one.
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
