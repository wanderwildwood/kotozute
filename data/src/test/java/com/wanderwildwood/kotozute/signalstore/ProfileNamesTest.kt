package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Showing somebody's name the way they write it.
 *
 * A profile carries given and family as two fields, and joining them given-then-family for
 * everybody gets CJKV names backwards. Ported from Signal's `ProfileName.getJoinedName` and
 * `CJKVUtil.isCJKV` so the same contact reads the same on both clients of one account.
 */
class ProfileNamesTest {

    @Test
    fun `a latin name is given then family`() {
        assertEquals("Lydia Nichole", ProfileNames.joined("Lydia", "Nichole"))
    }

    @Test
    fun `a japanese name is family then given`() {
        // 山田 is the family name and 太郎 the given one; written together it is 山田 太郎.
        // Joined the other way it is the same two words saying something else.
        assertEquals("山田 太郎", ProfileNames.joined("太郎", "山田"))
    }

    @Test
    fun `a korean name is family then given`() {
        assertEquals("김 민준", ProfileNames.joined("민준", "김"))
    }

    @Test
    fun `a chinese name is family then given`() {
        assertEquals("李 小龍", ProfileNames.joined("小龍", "李"))
    }

    @Test
    fun `a mixed name keeps the latin order`() {
        // Only when *every* non-empty part is CJKV does the order change. One latin part means
        // the reversal would be a guess, and upstream does not guess either.
        assertEquals("太郎 Smith", ProfileNames.joined("太郎", "Smith"))
        assertEquals("Taro 山田", ProfileNames.joined("Taro", "山田"))
    }

    @Test
    fun `one part on its own is just that part`() {
        assertEquals("Lydia", ProfileNames.joined("Lydia", null))
        assertEquals("山田", ProfileNames.joined(null, "山田"))
        assertEquals("Nichole", ProfileNames.joined("   ", "Nichole"))
    }

    @Test
    fun `nothing to show is null, not an empty string`() {
        assertNull(ProfileNames.joined(null, null))
        assertNull(ProfileNames.joined("", ""))
        // Whitespace is not a name. This is what the trim in `fromParts` is for.
        assertNull(ProfileNames.joined("  ", "\t"))
    }

    @Test
    fun `each part is trimmed`() {
        assertEquals("Lydia Nichole", ProfileNames.joined("  Lydia ", " Nichole  "))
    }

    @Test
    fun `the cjkv test reads the whole string`() {
        assertTrue(ProfileNames.isCjkv("山田"))
        assertTrue(ProfileNames.isCjkv("ひらがな"))
        assertTrue(ProfileNames.isCjkv("カタカナ"))
        assertTrue(ProfileNames.isCjkv("한글"))
        // A space is allowed inside a part -- a name with one in it is still a CJKV name.
        assertTrue(ProfileNames.isCjkv("山田 太郎"))
        // One latin character is enough to settle it, wherever it is.
        assertFalse(ProfileNames.isCjkv("山田A"))
        assertFalse(ProfileNames.isCjkv("A山田"))
        assertFalse(ProfileNames.isCjkv("Smith"))
    }

    @Test
    fun `an empty string is cjkv, as it is upstream`() {
        // Load-bearing in `getJoinedName`'s shape: a missing part must not veto the reversal.
        // The empty cases are answered before `joined` consults this, but the rule is kept the
        // same so the two implementations stay comparable.
        assertTrue(ProfileNames.isCjkv(""))
    }

    @Test
    fun `a part longer than the field is cut to fit`() {
        val long = "a".repeat(ProfileNames.MAX_PART_LENGTH + 50)
        val joined = ProfileNames.joined(long, null)!!
        assertEquals(ProfileNames.MAX_PART_LENGTH, joined.length)
    }

    @Test
    fun `the cut is by bytes, not characters`() {
        // A kanji is three bytes of UTF-8, so the cap is a third as many characters. Counting
        // characters would let a name through that the field cannot hold.
        val kanji = "山".repeat(100)
        val cut = ProfileNames.trimToFit(kanji, ProfileNames.MAX_PART_LENGTH)
        assertEquals(ProfileNames.MAX_PART_LENGTH / 3, cut.length)
        assertTrue(cut.toByteArray(Charsets.UTF_8).size <= ProfileNames.MAX_PART_LENGTH)
    }

    @Test
    fun `the cut does not split a character or a grapheme`() {
        // Cutting mid-codepoint makes invalid UTF-8; cutting through a combining sequence or a
        // family emoji makes a different string rather than a shorter one.
        val flag = "🇯🇵".repeat(40)
        val cut = ProfileNames.trimToFit(flag, ProfileNames.MAX_PART_LENGTH)
        assertTrue(cut.toByteArray(Charsets.UTF_8).size <= ProfileNames.MAX_PART_LENGTH)
        // Whole flags only: each is 8 bytes, so a clean cut leaves a multiple of its length.
        assertEquals(0, cut.length % "🇯🇵".length)
        assertTrue(cut.isNotEmpty())
    }

    @Test
    fun `a name that fits is left alone`() {
        assertEquals("Lydia", ProfileNames.trimToFit("Lydia", ProfileNames.MAX_PART_LENGTH))
        assertEquals("", ProfileNames.trimToFit("", ProfileNames.MAX_PART_LENGTH))
    }
}
