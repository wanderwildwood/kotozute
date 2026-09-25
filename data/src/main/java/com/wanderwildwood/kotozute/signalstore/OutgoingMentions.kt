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

    data class Encoded(val body: String, val mentions: List<Mention>)

    /** [names] is member ACI to the name shown for them. */
    fun encode(body: String, names: Map<String, String>): Encoded {
        val candidates = names.entries
            .filter { it.value.isNotBlank() }
            .sortedByDescending { it.value.length }
        if (candidates.isEmpty() || !body.contains('@')) return Encoded(body, emptyList())

        val out = StringBuilder()
        val mentions = mutableListOf<Mention>()
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
                mentions += Mention(hit.key, out.length, PLACEHOLDER.length)
                out.append(PLACEHOLDER)
                i += 1 + hit.value.length
            } else {
                out.append(body[i])
                i++
            }
        }
        return Encoded(out.toString(), mentions)
    }
}
