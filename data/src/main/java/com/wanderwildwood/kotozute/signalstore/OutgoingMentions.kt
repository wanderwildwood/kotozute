package com.wanderwildwood.kotozute.signalstore

/**
 * Turns "@Name" in a group message into the mention Signal sends.
 *
 * A mention on the wire is not text: the body carries one U+FFFC per mention and a body range
 * names who it is (`MentionUtil.getMentionSettings` / `BodyRangeUtil` upstream). Sent as plain
 * text, "@Ada" reaches Ada as three letters -- no mention notification, and in a muted group
 * set to "notify for mentions" nothing at all.
 *
 * Only whole names, preceded by the start or a space and not running on into another word, and
 * the longest name first, so "@Ada Whitlock" is Ada Whitlock and not Ada followed by a word.
 */
object OutgoingMentions {

    const val PLACEHOLDER = "￼"

    data class Mention(val aci: String, val start: Int, val length: Int)

    data class Encoded(
        val body: String,
        val mentions: List<Mention>,
        /** The styles asked for, moved to where their text now is. */
        val styles: List<BodyStyles.Range> = emptyList()
    )

    /**
     * [names] is member ACI to the name shown for them. [styles] are ranges over [body] as
     * typed; a style that takes in part of a name takes in the whole mention, as a received
     * one is widened in [BodyStyles.apply].
     */
    fun encode(body: String, names: Map<String, String>, styles: List<BodyStyles.Range> = emptyList()): Encoded {
        val candidates = names.entries
            .filter { it.value.isNotBlank() }
            .sortedByDescending { it.value.length }
        if (candidates.isEmpty() || !body.contains('@')) return Encoded(body, emptyList(), styles)

        val out = StringBuilder()
        val mentions = mutableListOf<Mention>()
        // Where each offset in [body] lands in the output, as a start and as an end: the
        // same inside a mention's name would put half a style on a single character.
        val startAt = IntArray(body.length + 1)
        val endAt = IntArray(body.length + 1)
        var i = 0
        while (i < body.length) {
            val atStart = body[i] == '@' && (i == 0 || body[i - 1].isWhitespace())
            val hit = if (atStart) {
                candidates.firstOrNull { (_, name) ->
                    body.startsWith(name, i + 1, ignoreCase = true) &&
                        (i + 1 + name.length).let { end -> end == body.length || !body[end].isLetterOrDigit() }
                }
            } else null
            if (hit != null) {
                val width = 1 + hit.value.length
                for (k in i until i + width) startAt[k] = out.length
                for (k in i + 1..i + width) endAt[k] = out.length + PLACEHOLDER.length
                endAt[i] = out.length
                mentions += Mention(hit.key, out.length, PLACEHOLDER.length)
                out.append(PLACEHOLDER)
                i += width
            } else {
                startAt[i] = out.length
                endAt[i] = out.length
                out.append(body[i])
                i++
            }
        }
        startAt[body.length] = out.length
        endAt[body.length] = out.length
        val moved = styles
            .filter { it.start >= 0 && it.length > 0 && it.start + it.length <= body.length }
            .mapNotNull { r ->
                val s = startAt[r.start]
                val e = endAt[r.start + r.length]
                if (e > s) BodyStyles.Range(s, e - s, r.style) else null
            }
        return Encoded(out.toString(), mentions, moved)
    }
}
