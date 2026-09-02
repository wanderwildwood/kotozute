package com.wanderwildwood.kotozute.model

import io.realm.RealmObject
import io.realm.annotations.PrimaryKey

/**
 * A Signal conversation. Separate from [Conversation] for the same reason
 * [SignalMessage] is separate from [Message]: a full sync deletes every Conversation row.
 */
open class SignalThread : RealmObject() {

    @PrimaryKey var threadKey: String = ""

    /** "direct" or "group". */
    var kind: String = "direct"

    /** Contact or group name as the bridge resolved it; may be empty. */
    var title: String = ""

    /** The other party, for a direct thread. Used to pair with an SMS thread. */
    var counterpartUuid: String = ""
    var counterpartNumber: String = ""

    /** Preview of the most recent message, so the inbox row says something. */
    var snippet: String = ""

    /** Whether that preview is our own message, which the row prefixes accordingly. */
    var snippetOutgoing: Boolean = false

    var lastTs: Long = 0
    var unread: Int = 0
    var archived: Boolean = false

    /** Kept at the top of the list, as a pinned SMS conversation is. */
    var pinned: Boolean = false

    /** No notification for this thread. Messages still arrive and still count as unread. */
    var muted: Boolean = false
}
