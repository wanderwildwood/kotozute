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

        val groupId = dataMessage.groupV2?.masterKey?.let { key ->
            android.util.Base64.encodeToString(key.toByteArray(), android.util.Base64.NO_WRAP)
        }.orEmpty()

        // Note to Self, by whichever identifier the envelope happens to carry.
        //
        // The bridge only had to handle the destination being absent entirely, because that is
        // what signal-cli reports. The raw protobuf is not so tidy: a note to self arrives with
        // the destination **populated with our own number**, so testing for absence alone filed
        // it under `direct:+1555...` while the bridge filed the same conversation under
        // `direct:<aci>` -- two Note to Self threads for one conversation. Measured, not
        // hypothetical.
        //
        // So: no destination at all, or a destination that is us, both mean Note to Self, and
        // both key off the ACI so the two rails agree.
        //
        // **Both** halves must be checked. Testing the uuid alone filed a message sent to a
        // real person as Note to Self whenever only their number was known -- which happens for
        // a recipient not yet resolved to an ACI -- and a reply in that thread then went to
        // yourself.
        val destinationIsSelf = (counterpartUuid.isNotBlank() && counterpartUuid == selfAci) ||
            (counterpartNumber.isNotBlank() && counterpartNumber == selfE164)
        val destinationIsAbsent = counterpartUuid.isBlank() && counterpartNumber.isBlank()
        if (outgoing && groupId.isBlank() && (destinationIsAbsent || destinationIsSelf)) {
            counterpartUuid = selfAci.orEmpty().ifBlank { authorUuid }
            counterpartNumber = ""
        }

        val threadKey = when {
            groupId.isNotBlank() -> "group:$groupId"
            else -> {
                val id = counterpartUuid.ifBlank { counterpartNumber }
                // Nothing to hang a thread on.
                if (id.isBlank()) return null else "direct:$id"
            }
        }

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
            // Identity of a Signal message is (author, timestamp). Stable across the several
            // notifications one message produces, and across a re-import -- which is what
            // keeps a redelivered envelope from becoming a second copy in the thread.
            id = "${authorUuid.ifBlank { authorNumber }}:$timestamp",
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
            reactionRemove = reaction?.remove == true
        )
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
