package com.wanderwildwood.kotozute.signalstore

import org.json.JSONArray

/**
 * A message's text styles: bold, italic, spoiler, strikethrough and monospace.
 *
 * Signal sends these as body ranges beside the text, the way it sends mentions -- the same
 * `BodyRange` list, with `style` set instead of a mention's service id. Upstream draws them from
 * `MessageStyler.style`; before this, every one was dropped, which for a spoiler meant showing
 * in the clear what the sender had hidden.
 *
 * Mentions are written into the text here (see [ContentNormalizer]) and change its length, so
 * the style ranges are moved to match in the same pass. Kept apart from the protobuf so it can
 * be tested.
 */
object BodyStyles {

    /** One styled stretch of the text as stored: [start] and [length] in UTF-16 units. */
    data class Range(val start: Int, val length: Int, val style: Style)

    /** Upstream's `BodyRange.Style`, less NONE. [code] is what is stored. */
    enum class Style(val code: String) {
        BOLD("b"), ITALIC("i"), SPOILER("s"), STRIKETHROUGH("t"), MONOSPACE("m");

        companion object {
            fun ofCode(code: String): Style? = entries.firstOrNull { it.code == code }
        }
    }

    /** A mention, before its name is written in: where it is, and what replaces it. */
    data class Mention(val start: Int, val length: Int, val replacement: String)

    /**
     * Writes [mentions] into [body] and moves [styles] to where their text now is.
     *
     * A style that starts or ends inside a mention is widened to take in all of the name: the
     * mention was one character, and half a name in bold is not what anybody styled. Ranges
     * outside the text, empty, or overlapping mentions are dropped, as upstream's are.
     */
    fun apply(body: String, mentions: List<Mention>, styles: List<Range>): Pair<String, List<Range>> {
        val edits = mentions
            .filter { it.start >= 0 && it.length > 0 && it.start + it.length <= body.length }
            .sortedBy { it.start }
            .fold(mutableListOf<Mention>()) { kept, m ->
                if (kept.isEmpty() || m.start >= kept.last().start + kept.last().length) kept += m
                kept
            }

        // Where an offset in the original text lands after the names are written in.
        fun moved(offset: Int, isEnd: Boolean): Int {
            var shift = 0
            for (e in edits) {
                val end = e.start + e.length
                when {
                    offset >= end -> shift += e.replacement.length - e.length
                    offset > e.start -> return if (isEnd) e.start + shift + e.replacement.length else e.start + shift
                    else -> return offset + shift
                }
            }
            return offset + shift
        }

        val out = StringBuilder(body)
        edits.asReversed().forEach { out.replace(it.start, it.start + it.length, it.replacement) }

        val placed = styles
            .filter { it.start >= 0 && it.length > 0 && it.start + it.length <= body.length }
            .mapNotNull { r ->
                val s = moved(r.start, isEnd = false)
                val e = moved(r.start + r.length, isEnd = true)
                if (e > s) Range(s, e - s, r.style) else null
            }
        return out.toString() to placed
    }

    /** Stored beside the message: `[[start,length,"b"], ...]`, or empty for none. */
    fun encode(ranges: List<Range>): String =
        if (ranges.isEmpty()) "" else JSONArray(ranges.map { JSONArray(listOf(it.start, it.length, it.style.code)) }).toString()

    fun decode(json: String?): List<Range> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val r = a.optJSONArray(i) ?: return@mapNotNull null
                val style = Style.ofCode(r.optString(2)) ?: return@mapNotNull null
                Range(r.optInt(0, -1), r.optInt(1, -1), style).takeIf { it.start >= 0 && it.length > 0 }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * [text] with every spoiler blanked, for anywhere a spoiler cannot be tapped to reveal:
     * the conversation list, a notification, the lock screen. Upstream shows these as a
     * shaded block; a run of the same block character reads the same on e-ink.
     */
    fun withSpoilersHidden(text: String, ranges: List<Range>): String {
        val spoilers = ranges.filter { it.style == Style.SPOILER }
        if (spoilers.isEmpty()) return text
        val out = StringBuilder(text)
        spoilers.forEach { r ->
            val end = minOf(r.start + r.length, out.length)
            for (i in r.start until end) if (!out[i].isWhitespace()) out.setCharAt(i, HIDDEN)
        }
        return out.toString()
    }

    const val HIDDEN = '█'
}
