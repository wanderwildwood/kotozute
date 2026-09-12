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

    /**
     * How long messages in this conversation last, in seconds. 0 means they do not disappear.
     *
     * A property of the conversation, not of a message. Signal keeps it on the recipient and
     * stamps every outgoing message from it; without it this phone replied into a
     * disappearing thread with messages that never disappear -- and, because a message with no
     * timer reads as a timer of zero, told the other person's client the conversation had been
     * turned off. They chose the setting; we were quietly undoing it.
     */
    var expiresInSeconds: Long = 0

    /**
     * Which change to [expiresInSeconds] is the current one.
     *
     * Signal resolves conflicting timer changes by version rather than by arrival order, so a
     * late-delivered old change cannot undo a newer one. It must also be echoed on every
     * message we send, or a modern peer treats ours as older than whatever it holds.
     */
    var expireTimerVersion: Int = 0
}
