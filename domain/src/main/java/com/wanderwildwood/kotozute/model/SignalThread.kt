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

    /** The contact's or group's name as this device resolved it; may be empty. */
    var title: String = ""

    /**
     * The group's master key, for a group thread.
     *
     * Everything about a group is fetched from the server with this -- its name, its
     * members, whether it takes messages from anybody. It used to be read off whichever
     * message in the thread happened to carry one, which meant a group whose messages had
     * all expired or been deleted could no longer be written to, and a group just made on
     * this phone could not be written to at all. Signal keeps it on the group record
     * (`GroupTable.V2GroupProperties.getGroupMasterKey`), one per group; this is that row.
     */
    var groupMasterKey: ByteArray? = null

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

    /**
     * When somebody marked this conversation unread, on this phone or another, or 0.
     *
     * Signal's `markedUnread` on the account's records, which is what makes "mark unread"
     * reach every device: set here, it is written to the account; set elsewhere, it arrives
     * here. A time rather than a flag so that clearing it from another device can put back only
     * what the mark covered -- a message that arrived after it is still unread.
     */
    var markedUnreadAt: Long = 0
}
