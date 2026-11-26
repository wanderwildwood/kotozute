package com.wanderwildwood.kotozute.util

import java.util.concurrent.TimeUnit

/**
 * The rules of the Deleted folder, kept apart from Realm and Android so they can be tested.
 *
 * Delete used to remove a conversation at once -- from this app's database, from the phone's
 * SMS store, and for Signal from the other devices too -- with only a prompt in the way, and a
 * whole conversation was lost that way. Now it waits in Deleted, can be put back, and is
 * removed for good after [KEEP_DAYS] days by the same final delete as before.
 */
object DeletedFolder {

    const val KEEP_DAYS = 30L

    val KEEP_MS: Long = TimeUnit.DAYS.toMillis(KEEP_DAYS)

    /**
     * Whether a Delete puts the conversation in Deleted (true) or removes it at once (false).
     *
     * Only the setting decides. A delete that arrives from another Signal device, and a
     * message request turned down, never come this way: those were final where they were made.
     */
    fun holdsDelete(keepDeleted: Boolean): Boolean = keepDeleted

    /**
     * Whether a conversation put in Deleted at [deletedAt] is due to be removed for good at [now].
     *
     * 0 is "not in Deleted" and is never due. A stamp later than [now] -- the clock was set
     * back, or was wrong when it was deleted -- is not due either: it still waits its 30 days
     * from the stamp, because waiting too long is the mistake that can be undone.
     */
    fun isDue(deletedAt: Long, now: Long): Boolean =
        deletedAt > 0 && now - deletedAt >= KEEP_MS
}
