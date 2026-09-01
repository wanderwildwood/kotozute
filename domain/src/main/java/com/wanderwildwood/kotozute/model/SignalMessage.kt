package com.wanderwildwood.kotozute.model

import io.realm.RealmObject
import io.realm.annotations.Index
import io.realm.annotations.PrimaryKey

/**
 * A Signal message, kept deliberately apart from [Message].
 *
 * [Message] is a mirror of the telephony content provider, and a full sync calls
 * SyncRepositoryImpl.removeOldMessages(), which deletes every Message, Conversation,
 * MmsPart and Recipient row before rebuilding them from the provider. Signal rows in
 * those tables would be destroyed by an ordinary re-sync, so they live here instead.
 *
 * The primary key is the bridge's message id -- "<authorUuid>:<timestamp>" -- which is
 * how Signal itself identifies a message. Keying on it means a message can arrive twice
 * (one logical message can produce several notifications, and an imported backup can
 * re-deliver what we already hold) without ever duplicating.
 */
open class SignalMessage : RealmObject() {

    @PrimaryKey var id: String = ""

    /** Bridge-assigned cursor. Sync asks for everything after the highest seq we hold. */
    @Index var seq: Long = 0

    /** "direct:<uuid>" or "group:<groupId>". */
    @Index var threadKey: String = ""

    /** Signal message timestamp, ms. Not necessarily in seq order -- an import backfills. */
    @Index var date: Long = 0

    var senderUuid: String = ""
    var senderNumber: String = ""

    /** True for messages this account sent, from any device, including Note to Self. */
    var outgoing: Boolean = false

    var body: String = ""
    var groupId: String = ""
    var quoteTs: Long = 0
    var read: Boolean = false

    /** "live" from the bridge's stream, "import" from a restored Signal backup. */
    var source: String = "live"

    /**
     * The bridge's attachment list, verbatim JSON. Realm cannot hold a list of plain
     * objects without another RealmObject per row, and nothing queries inside this --
     * it is read once when a row is drawn.
     */
    var attachments: String = ""
}
