package com.wanderwildwood.kotozute.signalstore

/**
 * A vote on a poll, or a poll ended: upstream's `DataMessage.pollVote` / `pollTerminate`.
 * The poll is named as Signal names a message, by [targetAuthor] and [targetSentAt].
 */
data class PollAction(
    val threadKey: String,
    /** Who voted or ended it: the sender, or this account for a transcript of our own. */
    val by: String,
    val sentAt: Long,
    val targetAuthor: String,
    val targetSentAt: Long,
    val end: Boolean,
    val voteCount: Int,
    val options: List<Int>
)
