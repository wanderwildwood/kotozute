package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.whispersystems.signalservice.internal.push.DataMessage

/**
 * A message that carries a profile key and nothing anybody wrote.
 *
 * ⚠ It used to arrive as an **empty bubble**, and it is the one case [ContentNormalizer.describe]
 * could not save: there is no field on it to recognise. Body empty, no attachment, no reaction,
 * no group context — so it fell through every arm of `describe` to `null` and was stored blank.
 * Somebody changes their profile name or picture, or rotates a key after a block, and a silent
 * gap appears in the conversation.
 *
 * Upstream inserts nothing for it either: `SyncMessageProcessor:286`.
 *
 * ⚠ **The flag, not the emptiness.** An ordinary message carries a profile key too — that is how
 * they are learned at all — so "has a key and no body" would be the wrong test in one direction
 * and "has a key" catastrophically wrong in the other.
 */
class ProfileKeyUpdateTest {

    private val flag = DataMessage.Flags.PROFILE_KEY_UPDATE.value

    @Test
    fun `a message flagged as a profile key update is one`() {
        assertTrue(ContentNormalizer.isProfileKeyUpdate(DataMessage(flags = flag)))
    }

    @Test
    fun `an ordinary message carrying a profile key is not one`() {
        val ordinary = DataMessage(body = "are you coming?", profileKey = okio.ByteString.of(*ByteArray(32)))
        assertFalse(ContentNormalizer.isProfileKeyUpdate(ordinary))
    }

    @Test
    fun `a message with no flags at all is not one`() {
        assertFalse(ContentNormalizer.isProfileKeyUpdate(DataMessage(body = "hello")))
    }

    /**
     * The flags field is a bitmask, so a profile key update that also carries another flag is
     * still a profile key update. Testing equality instead of a mask would miss it.
     */
    @Test
    fun `the flag is read as a bit, not as the whole value`() {
        val both = flag or DataMessage.Flags.FORWARD.value
        assertTrue(ContentNormalizer.isProfileKeyUpdate(DataMessage(flags = both)))
    }
}
