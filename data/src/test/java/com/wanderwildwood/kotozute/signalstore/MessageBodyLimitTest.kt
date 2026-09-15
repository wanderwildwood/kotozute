package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long a message body may be before no recipient will accept it.
 *
 * `EnvelopeContentValidator` discards a data message whose body is over
 * `SignalServiceMessageLimits.MAX_INLINE_BODY_SIZE_BYTES`, so an over-long message is taken by
 * the server, reported delivered by this phone, and never shown to the person it was written
 * to. There was no check here at all; this is the boundary the check has to land on exactly,
 * because being one byte out in either direction either refuses a good message or lets a lost
 * one through.
 */
class MessageBodyLimitTest {

    private val limit = SignalSender.MAX_INLINE_BODY_SIZE_BYTES

    @Test
    fun `the limit is the one upstream uses`() {
        // `SignalServiceMessageLimits.kt:12` -- 2.kibiBytes. Copied rather than referenced,
        // because the fork of signal-service this depends on predates the class.
        assertEquals(2048, limit)
    }

    @Test
    fun `an ordinary message is sent`() {
        assertFalse(SignalSender.isBodyTooLong("Running late, about twenty minutes."))
        assertFalse(SignalSender.isBodyTooLong(""))
    }

    @Test
    fun `the boundary is exact`() {
        val atTheLimit = "a".repeat(limit)
        assertFalse("a body of exactly the limit is allowed", SignalSender.isBodyTooLong(atTheLimit))
        assertTrue("one byte over is not", SignalSender.isBodyTooLong(atTheLimit + "a"))
    }

    @Test
    fun `length is counted in bytes, not characters`() {
        // The trap this function exists to avoid. `String.length` counts UTF-16 units, so a
        // message of emoji is well under any character count and well over the byte limit --
        // and it is exactly the kind of message that would have gone out and vanished.
        val emoji = "🌲".repeat(600)
        assertTrue("600 emoji is only ${emoji.length} UTF-16 units", emoji.length < limit)
        assertEquals(2400, SignalSender.utf8Size(emoji))
        assertTrue(SignalSender.isBodyTooLong(emoji))

        // Japanese is three bytes a character, so about six hundred and eighty characters is the real
        // ceiling for a message written in it -- a third of what an English one gets.
        val kana = "あ".repeat(682)
        assertFalse(SignalSender.isBodyTooLong(kana))
        assertTrue(SignalSender.isBodyTooLong(kana + "あ"))
    }
}
