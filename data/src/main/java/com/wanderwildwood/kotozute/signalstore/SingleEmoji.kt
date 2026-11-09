package com.wanderwildwood.kotozute.signalstore

/**
 * Whether a piece of text is one emoji and nothing else.
 *
 * Upstream checks a sticker's emoji with `EmojiUtil.isEmoji` (0ab9936dec) before showing it,
 * because the field is whatever the sender's client put there: a sticker could arrive "with an
 * emoji" that is in fact a sentence, and that sentence would stand in the conversation and the
 * notification as if it were the message.
 *
 * Upstream answers from its emoji table, which this app does not carry. This answers from the
 * shape Unicode gives an emoji (UTS #51): one pictograph, optionally with a presentation
 * selector, a skin tone and a tag sequence, joined to others only by zero-width joiners; or a
 * flag (two regional indicators); or a keycap. Close enough for the job, which is to keep text
 * out: anything with a letter, a space, or two emoji side by side is refused.
 */
internal object SingleEmoji {

    private const val ZWJ = 0x200D
    private const val VS16 = 0xFE0F
    private const val KEYCAP = 0x20E3
    private const val TAG_END = 0xE007F

    /**
     * Longer than any real emoji. The longest in use -- a family of four, each with a skin
     * tone -- is 35 UTF-16 units; this also keeps a crafted run of joiners cheap to refuse.
     */
    private const val MAX_LENGTH = 64

    fun isSingleEmoji(text: String?): Boolean {
        if (text.isNullOrEmpty() || text.length > MAX_LENGTH) return false
        val cps = text.codePoints().toArray()

        if (cps.size == 2 && cps.all(::isRegionalIndicator)) return true
        if (isKeycap(cps)) return true

        var i = 0
        while (true) {
            i = element(cps, i)
            if (i < 0) return false
            if (i == cps.size) return true
            if (cps[i] != ZWJ) return false
            i++
            if (i == cps.size) return false
        }
    }

    /** One pictograph and what may follow it; the index after it, or -1 when there is none. */
    private fun element(cps: IntArray, start: Int): Int {
        if (start >= cps.size || !isPictograph(cps[start])) return -1
        var i = start + 1
        if (i < cps.size && cps[i] == VS16) i++
        if (i < cps.size && isSkinTone(cps[i])) i++
        if (i < cps.size && isTag(cps[i])) {
            while (i < cps.size && isTag(cps[i])) i++
            if (i >= cps.size || cps[i] != TAG_END) return -1
            i++
        }
        return i
    }

    private fun isKeycap(cps: IntArray): Boolean {
        if (cps.isEmpty()) return false
        val base = cps[0]
        if (!(base in '0'.code..'9'.code || base == '#'.code || base == '*'.code)) return false
        return (cps.size == 2 && cps[1] == KEYCAP) ||
            (cps.size == 3 && cps[1] == VS16 && cps[2] == KEYCAP)
    }

    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF
    private fun isSkinTone(cp: Int) = cp in 0x1F3FB..0x1F3FF
    private fun isTag(cp: Int) = cp in 0xE0020..0xE007E

    /** Code points that are drawn as an emoji, after Unicode's Extended_Pictographic. */
    private fun isPictograph(cp: Int): Boolean = when {
        isRegionalIndicator(cp) || isSkinTone(cp) -> false
        cp in 0x1F000..0x1FAFF -> true
        cp in 0x1FC00..0x1FFFD -> true
        cp in 0x2600..0x27BF -> true
        cp in 0x2194..0x2199 || cp in 0x21A9..0x21AA -> true
        cp in 0x231A..0x231B || cp == 0x2328 || cp == 0x23CF -> true
        cp in 0x23E9..0x23F3 || cp in 0x23F8..0x23FA -> true
        cp in 0x25AA..0x25AB || cp == 0x25B6 || cp == 0x25C0 || cp in 0x25FB..0x25FE -> true
        cp in 0x2934..0x2935 || cp in 0x2B05..0x2B07 || cp in 0x2B1B..0x2B1C -> true
        cp == 0x2B50 || cp == 0x2B55 || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299 -> true
        cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x2139 || cp == 0x24C2 -> true
        else -> false
    }
}
