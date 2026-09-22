package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Writing this account's own profile name into the one field a profile carries.
 *
 * Only a phone that **registered** its own account ever writes a profile -- a linked device
 * inherits the primary's. So this path has no second reader to catch it: whatever it writes
 * is what every correspondent sees, and a wrong separator produces a name that is wrong on
 * their screen and right on this one.
 *
 * Ported case for case from Signal's `ProfileName.serialize`.
 */
class ProfileNameSerializeTest {

    @Test
    fun `both parts are joined by a NUL`() {
        assertEquals("Ada\u0000Lovelace", ProfileNames.serialize("Ada", "Lovelace"))
    }

    /**
     * ⚠ NUL, not a space. A space is what a *reader* sees once the parts are joined for
     * display; writing one here would make a given name that contains a space -- which is an
     * ordinary thing for a name to contain -- come back from the server as two parts.
     */
    @Test
    fun `a given name containing a space survives the round trip`() {
        val serialized = ProfileNames.serialize("Mary Anne", "Evans")
        assertEquals(listOf("Mary Anne", "Evans"), serialized.split('\u0000'))
    }

    @Test
    fun `a given name alone stands by itself, with no separator`() {
        assertEquals("Snufkin", ProfileNames.serialize("Snufkin", ""))
    }

    /** Signal's rule: no given name is no name, whatever the family field says. */
    @Test
    fun `no given name serialises to nothing`() {
        assertEquals("", ProfileNames.serialize("", "Lovelace"))
        assertEquals("", ProfileNames.serialize(null, "Lovelace"))
        assertEquals("", ProfileNames.serialize("   ", "Lovelace"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("Ada\u0000Lovelace", ProfileNames.serialize("  Ada ", " Lovelace  "))
    }

    /**
     * ⚠ Capped in **bytes**, not characters, and the server does not enforce it: an over-long
     * part is accepted, stored, and read back by every other client as something else.
     */
    @Test
    fun `an over-long part is capped at the byte limit`() {
        val long = "a".repeat(ProfileNames.MAX_PART_LENGTH * 2)
        val serialized = ProfileNames.serialize(long, "")
        assertEquals(ProfileNames.MAX_PART_LENGTH, serialized.toByteArray(Charsets.UTF_8).size)
    }

    /** A kanji name spends three bytes a character, so the cap lands in a different place. */
    @Test
    fun `a multibyte part is capped by bytes rather than by characters`() {
        val long = "田".repeat(ProfileNames.MAX_PART_LENGTH)
        val serialized = ProfileNames.serialize(long, "")
        val bytes = serialized.toByteArray(Charsets.UTF_8).size
        assert(bytes <= ProfileNames.MAX_PART_LENGTH) { "cap exceeded: $bytes bytes" }
        // And it was cut on a character boundary, not through one.
        assertEquals(serialized, String(serialized.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    /**
     * The counterpart of the split in `SignalProfiles.fetch`: what this writes is what that
     * reads. Kept here because the two live in different files and only agree by intent.
     */
    @Test
    fun `what is written is what the reader splits`() {
        val serialized = ProfileNames.serialize("山田", "太郎")
        val parts = serialized.split('\u0000').map { it.trim('\u0000') }
        assertEquals("山田", parts[0])
        assertEquals("太郎", parts[1])
        // And the display join puts a CJKV name in the order its owner writes it.
        assertEquals("太郎 山田", ProfileNames.joined(parts[0], parts[1]))
    }
}
