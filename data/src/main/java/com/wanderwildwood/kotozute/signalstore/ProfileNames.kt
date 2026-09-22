package com.wanderwildwood.kotozute.signalstore

import java.nio.charset.StandardCharsets
import java.text.BreakIterator

/**
 * Putting somebody's two name parts together in the order they write them.
 *
 * A profile carries a given name and a family name as two separate fields, and the app has to
 * decide how to show them. Joining them given-then-family is the English answer, and applying
 * it to everybody gets Japanese, Chinese and Korean names backwards -- 山田太郎 shown as
 * 太郎 山田, which is not how the person writes their own name and not what Signal shows
 * either, so the same contact reads differently on two clients of the same account.
 *
 * Ported from Signal's `ProfileName` (`getJoinedName`, `fromParts`) and `CJKVUtil.isCJKV`.
 * It lives on its own because this app joins names in two places -- the profile fetch and the
 * storage service record -- and they were each doing it their own way.
 */
internal object ProfileNames {

    /**
     * The longest a single part may be, in UTF-8 bytes.
     *
     * Signal's `ProfileName.MAX_PART_LENGTH`, which is `(MAX_POSSIBLE_NAME_LENGTH - 1) / 2`
     * with `MAX_POSSIBLE_NAME_LENGTH = 257`. Bytes rather than characters because that is what
     * the field holds, and a name in kanji spends three bytes a character.
     */
    const val MAX_PART_LENGTH = 128

    /**
     * The two parts as one name, or null when there is nothing to show.
     *
     * Each part is trimmed and capped first, as Signal's `fromParts` does before anything else
     * sees them: a stored name can be longer than the field is meant to hold, and a name that
     * is all whitespace is not a name.
     */
    fun joined(given: String?, family: String?): String? {
        val g = trimToFit(given.orEmpty().trim(), MAX_PART_LENGTH)
        val f = trimToFit(family.orEmpty().trim(), MAX_PART_LENGTH)
        return when {
            g.isEmpty() && f.isEmpty() -> null
            g.isEmpty() -> f
            f.isEmpty() -> g
            // The one branch this exists for. Both parts CJKV means family first.
            isCjkv(g) && isCjkv(f) -> "$f $g"
            else -> "$g $f"
        }
    }

    /**
     * The two parts as the **one field** a profile actually carries.
     *
     * The counterpart of the split in [SignalProfiles.fetch], and here rather than there so
     * the separator is written and read in one file. Signal's `ProfileName.serialize`, case
     * for case: an empty given name serialises to the empty string whatever the family name
     * says, a lone given name stands by itself, and only both together take the NUL.
     *
     * ⚠ NUL, not a space. A space is what a reader sees between the parts once they are
     * joined for display, and writing one here would make a single given name containing a
     * space -- which is an ordinary thing for a name to contain -- come back as two parts.
     *
     * Each part is trimmed and capped exactly as [joined] does, because the server is not the
     * thing that enforces this: an over-long part is accepted, stored, and then read back by
     * every other client as something else.
     */
    fun serialize(given: String?, family: String?): String {
        val g = trimToFit(given.orEmpty().trim(), MAX_PART_LENGTH)
        val f = trimToFit(family.orEmpty().trim(), MAX_PART_LENGTH)
        return when {
            g.isEmpty() -> ""
            f.isEmpty() -> g
            else -> "$g\u0000$f"
        }
    }

    /**
     * Whether every character is one a CJKV name is written with.
     *
     * Signal's `CJKVUtil.isCJKV`, block for block, including the space -- a name with a space
     * in it is still a CJKV name. ⚠ An **empty** string is CJKV upstream, which matters: it
     * makes `isCJKV` on a missing part not veto the reversal. Here the empty cases are
     * answered before this is ever called, but the rule is kept the same so the two can be
     * compared.
     */
    fun isCjkv(value: String): Boolean {
        if (value.isEmpty()) return true
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            if (!isCodePointCjkv(codePoint)) return false
            offset += Character.charCount(codePoint)
        }
        return true
    }

    private fun isCodePointCjkv(codePoint: Int): Boolean {
        if (codePoint == ' '.code) return true
        val block = Character.UnicodeBlock.of(codePoint)
        return block in CJKV_BLOCKS || Character.isIdeographic(codePoint)
    }

    /**
     * The longest prefix of [value] that fits in [maxByteLength] bytes of UTF-8.
     *
     * Signal's `StringUtil.trimToFit`. Cut on **grapheme** boundaries, not characters: cutting
     * mid-codepoint makes invalid UTF-8, and cutting between a base character and its combining
     * mark -- or through a family emoji -- makes a different string rather than a shorter one.
     */
    fun trimToFit(value: String, maxByteLength: Int): String {
        if (value.isEmpty()) return ""
        if (value.toByteArray(StandardCharsets.UTF_8).size <= maxByteLength) return value

        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(value)
        val out = StringBuilder()
        var used = 0
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val grapheme = value.substring(start, end)
            val size = grapheme.toByteArray(StandardCharsets.UTF_8).size
            if (used + size > maxByteLength) break
            out.append(grapheme)
            used += size
            start = end
            end = iterator.next()
        }
        return out.toString()
    }

    /** Signal's list, in its order. */
    private val CJKV_BLOCKS = setOf(
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B,
        Character.UnicodeBlock.CJK_COMPATIBILITY,
        Character.UnicodeBlock.CJK_COMPATIBILITY_FORMS,
        Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT,
        Character.UnicodeBlock.CJK_RADICALS_SUPPLEMENT,
        Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
        Character.UnicodeBlock.ENCLOSED_CJK_LETTERS_AND_MONTHS,
        Character.UnicodeBlock.KANGXI_RADICALS,
        Character.UnicodeBlock.IDEOGRAPHIC_DESCRIPTION_CHARACTERS,
        Character.UnicodeBlock.HIRAGANA,
        Character.UnicodeBlock.KATAKANA,
        Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS,
        Character.UnicodeBlock.HANGUL_JAMO,
        Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO,
        Character.UnicodeBlock.HANGUL_SYLLABLES
    )
}
