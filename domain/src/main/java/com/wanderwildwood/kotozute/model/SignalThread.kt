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

    var lastTs: Long = 0
    var unread: Int = 0
    var archived: Boolean = false
}
