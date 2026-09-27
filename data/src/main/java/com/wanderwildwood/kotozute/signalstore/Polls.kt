package com.wanderwildwood.kotozute.signalstore

import org.json.JSONArray
import org.json.JSONObject

/**
 * A poll, as kept beside the message that asked it: the question, the options, whether more
 * than one may be chosen, whether it has ended, and each voter's latest vote.
 *
 * The rules are upstream's (`DataMessageProcessor.handlePollVote`, `PollTables.insertVotes`):
 * every vote carries the voter's whole selection and a count that rises by one each time, so a
 * vote with a count no higher than the voter's last is an old one arriving late and changes
 * nothing; an empty selection takes the vote back; an index outside the options, or more than
 * one where only one may be chosen, or any vote on a poll that has ended, is refused. Limits
 * from `EnvelopeContentValidator`: 2 to 10 options, the question at most 200 characters, each
 * option at most 100. Kept apart from the protobuf so it can be tested.
 */
object Polls {

    const val MAX_QUESTION = 200
    const val MAX_OPTION = 100
    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 10

    data class Vote(val count: Int, val options: List<Int>)

    data class Poll(
        val question: String,
        val multiple: Boolean,
        val options: List<String>,
        val ended: Boolean = false,
        /** Voter's service id to their latest vote. */
        val votes: Map<String, Vote> = emptyMap()
    ) {
        /** How many voters chose each option, in the options' order. */
        fun tally(): List<Int> = options.indices.map { i -> votes.values.count { i in it.options } }
    }

    /** A poll as asked, or null when it breaks upstream's limits. */
    fun create(question: String, multiple: Boolean, options: List<String>): Poll? {
        val q = question.trim()
        val opts = options.map { it.trim() }
        if (q.isEmpty() || q.length > MAX_QUESTION) return null
        if (opts.size !in MIN_OPTIONS..MAX_OPTIONS) return null
        if (opts.any { it.isEmpty() || it.length > MAX_OPTION }) return null
        return Poll(q, multiple, opts)
    }

    /** [poll] with [voter]'s vote applied, or null when upstream would refuse it. */
    fun vote(poll: Poll, voter: String, count: Int, options: List<Int>): Poll? {
        if (poll.ended) return null
        if (count <= (poll.votes[voter]?.count ?: 0)) return null
        if (options.any { it !in poll.options.indices }) return null
        val chosen = options.distinct().sorted()
        if (!poll.multiple && chosen.size > 1) return null
        return poll.copy(votes = poll.votes + (voter to Vote(count, chosen)))
    }

    fun end(poll: Poll): Poll = poll.copy(ended = true)

    fun encode(poll: Poll): String = JSONObject()
        .put("q", poll.question)
        .put("multi", poll.multiple)
        .put("options", JSONArray(poll.options))
        .put("ended", poll.ended)
        .put("votes", JSONObject().apply {
            poll.votes.forEach { (voter, v) ->
                put(voter, JSONObject().put("count", v.count).put("options", JSONArray(v.options)))
            }
        })
        .toString()

    fun decode(json: String?): Poll? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(json)
            val opts = o.getJSONArray("options").let { a -> (0 until a.length()).map { a.getString(it) } }
            val votesJson = o.optJSONObject("votes") ?: JSONObject()
            val votes = votesJson.keys().asSequence().associateWith { voter ->
                val v = votesJson.getJSONObject(voter)
                val a = v.getJSONArray("options")
                Vote(v.getInt("count"), (0 until a.length()).map { a.getInt(it) })
            }
            Poll(o.getString("q"), o.optBoolean("multi"), opts, o.optBoolean("ended"), votes)
        }.getOrNull()
    }
}
