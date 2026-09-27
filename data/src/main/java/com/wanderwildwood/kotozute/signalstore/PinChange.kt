package com.wanderwildwood.kotozute.signalstore

/**
 * A message pinned or unpinned: upstream's `DataMessage.pinMessage` / `unpinMessage`.
 * [until] is when a pin lapses, [Long.MAX_VALUE] for one kept until unpinned; 0 for an unpin.
 */
data class PinChange(
    val threadKey: String,
    /** Who pinned it: the sender, or this account for a transcript of our own. */
    val by: String,
    val sentAt: Long,
    val targetAuthor: String,
    val targetSentAt: Long,
    val pin: Boolean,
    val until: Long
)
