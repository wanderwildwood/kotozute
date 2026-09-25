package com.wanderwildwood.kotozute.signalstore

import com.wanderwildwood.kotozute.signal.BridgeMessage
import java.util.Base64
import org.json.JSONObject

/**
 * A decrypted message as it waits in `unfiled`, between its envelope going and the message
 * database having it. See [ProtocolStoreSchema.UNFILED].
 *
 * Every field, because this is the only copy: whatever is left out here is lost from a
 * message that is filed from it.
 */
internal object UnfiledMessage {

    fun encode(m: BridgeMessage): String = JSONObject()
        .put("id", m.id)
        .put("threadKey", m.threadKey)
        .put("ts", m.ts)
        .put("senderUuid", m.senderUuid)
        .put("senderNumber", m.senderNumber)
        .put("outgoing", m.outgoing)
        .put("body", m.body)
        .put("groupId", m.groupId)
        .put("quoteTs", m.quoteTs)
        .put("read", m.read)
        .put("source", m.source)
        .put("attachmentsJson", m.attachmentsJson)
        .put("expiresAt", m.expiresAt)
        .put("expiresInSeconds", m.expiresInSeconds)
        .put("viewOnce", m.viewOnce)
        .put("reactionEmoji", m.reactionEmoji)
        .put("reactionTarget", m.reactionTarget)
        .put("reactionRemove", m.reactionRemove)
        .apply {
            m.groupMasterKey?.let { put("groupMasterKey", Base64.getEncoder().encodeToString(it)) }
        }
        .toString()

    fun decode(json: String): BridgeMessage {
        val o = JSONObject(json)
        return BridgeMessage(
            id = o.getString("id"),
            threadKey = o.getString("threadKey"),
            ts = o.getLong("ts"),
            senderUuid = o.getString("senderUuid"),
            senderNumber = o.getString("senderNumber"),
            outgoing = o.getBoolean("outgoing"),
            body = o.getString("body"),
            groupId = o.getString("groupId"),
            quoteTs = o.getLong("quoteTs"),
            read = o.getBoolean("read"),
            source = o.getString("source"),
            attachmentsJson = o.getString("attachmentsJson"),
            expiresAt = o.getLong("expiresAt"),
            expiresInSeconds = o.getLong("expiresInSeconds"),
            viewOnce = o.getBoolean("viewOnce"),
            reactionEmoji = o.getString("reactionEmoji"),
            reactionTarget = o.getString("reactionTarget"),
            reactionRemove = o.getBoolean("reactionRemove"),
            groupMasterKey = o.optString("groupMasterKey").takeIf { it.isNotEmpty() }
                ?.let { Base64.getDecoder().decode(it) }
        )
    }
}
