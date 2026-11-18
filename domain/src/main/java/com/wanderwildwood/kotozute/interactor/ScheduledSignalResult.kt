package com.wanderwildwood.kotozute.interactor

import com.wanderwildwood.kotozute.repository.IdentityNotice
import com.wanderwildwood.kotozute.repository.SentButNotFiled

/**
 * What one try at a scheduled Signal message means for its row in Scheduled.
 *
 * Only one outcome keeps the row: nothing reached the conversation, so Scheduled is the one
 * place it can be sent again from. The other two have already put the message in the
 * conversation, and keeping the row as well would send it a second time.
 */
object ScheduledSignalResult {

    enum class Outcome {
        /** It went. Also when it went and could not be filed: sending it again sends it twice. */
        SENT,

        /**
         * Refused for a changed safety number. The message is kept in the conversation, marked
         * not sent, and the notice there offers to accept the new key and send it.
         */
        WAITING_IN_THREAD,

        /** It did not go and nothing was kept in the conversation. The row stays. */
        NOT_SENT
    }

    fun of(failure: Throwable?): Outcome = when {
        failure == null -> Outcome.SENT
        generateSequence(failure) { it.cause }.take(8).any { it is SentButNotFiled } -> Outcome.SENT
        IdentityNotice.isIdentityProblem(failure) -> Outcome.WAITING_IN_THREAD
        else -> Outcome.NOT_SENT
    }

    /** The key a row is remembered under once it has not gone; a reused id with a new time is a new row. */
    fun key(id: Long, date: Long): String = "$id:$date"
}
