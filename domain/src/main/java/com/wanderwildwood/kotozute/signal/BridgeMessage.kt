package com.wanderwildwood.kotozute.signal

/*
 * Moved here from the data module so the domain layer can name it.
 *
 * The package is unchanged, so every existing import still resolves. The name is now a
 * misnomer -- messages reach this shape from the device's own Signal connection as well as
 * from a bridge -- but renaming it touches the whole rail and belongs in its own change.
 */

data class BridgeMessage(
    val id: String,
    val seq: Long,
    val threadKey: String,
    val ts: Long,
    val senderUuid: String,
    val senderNumber: String,
    val outgoing: Boolean,
    val body: String,
    val groupId: String,
    val quoteTs: Long,
    val read: Boolean,
    val source: String,
    /** The bridge's attachment array, kept as JSON; the app only reads it to draw a row. */
    val attachmentsJson: String,

    /**
     * When this copy must be gone, in ms, or 0 for never. The bridge sends it; the phone
     * has to honour it independently, because the bridge deletes only its own row and the
     * phone's copy is the one the user can still read.
     */
    val expiresAt: Long = 0,
    val expiresInSeconds: Long = 0,
    /** Signal intends this to be opened once. Its attachment is never stored. */
    val viewOnce: Boolean = false,

    /** Set only on a reaction row, which points at another message rather than being one. */
    val reactionEmoji: String = "",
    val reactionTarget: String = "",
    val reactionRemove: Boolean = false
)

