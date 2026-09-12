package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Whether a message goes out sealed, and what each send teaches.
 *
 * The rule this pins is the one the app had wrong: somebody whose profile key we have never
 * seen. Refusing to try -- the old behaviour -- meant every such conversation was named to the
 * server on every message, for ever, including for the many accounts that would have accepted
 * the message sealed. Ported from Signal's own two decisions so the cases stay comparable.
 */
class SealedSenderAccessTest {

    @Test
    fun `a stranger is tried, not given up on`() {
        assertEquals(
            SealedSender.Key.Random,
            SealedSender.keyFor(SEALED_SENDER_UNKNOWN, hasProfileKey = false)
        )
    }

    @Test
    fun `somebody who shared their profile key is sent with it`() {
        assertEquals(
            SealedSender.Key.Derived,
            SealedSender.keyFor(SEALED_SENDER_UNKNOWN, hasProfileKey = true)
        )
    }

    @Test
    fun `an account that accepts anything needs no key of theirs`() {
        assertEquals(
            SealedSender.Key.Random,
            SealedSender.keyFor(SEALED_SENDER_UNRESTRICTED, hasProfileKey = false)
        )
    }

    @Test
    fun `an account that requires their key, without it, is not guessed at`() {
        assertEquals(
            SealedSender.Key.None,
            SealedSender.keyFor(SEALED_SENDER_ENABLED, hasProfileKey = false)
        )
    }

    @Test
    fun `an account that refused is not asked again`() {
        // Even once we hold their key -- the reset that makes that reachable happens where the
        // key is written, not here.
        assertEquals(
            SealedSender.Key.None,
            SealedSender.keyFor(SEALED_SENDER_DISABLED, hasProfileKey = true)
        )
    }

    @Test
    fun `a sealed send with no key of theirs proves they accept anything`() {
        assertEquals(
            SEALED_SENDER_UNRESTRICTED,
            SealedSender.modeAfter(SEALED_SENDER_UNKNOWN, unidentified = true, hasProfileKey = false)
        )
    }

    @Test
    fun `a sealed send with their key proves only that it works`() {
        assertEquals(
            SEALED_SENDER_ENABLED,
            SealedSender.modeAfter(SEALED_SENDER_UNKNOWN, unidentified = true, hasProfileKey = true)
        )
    }

    @Test
    fun `a send that had to fall back settles it`() {
        assertEquals(
            SEALED_SENDER_DISABLED,
            SealedSender.modeAfter(SEALED_SENDER_UNKNOWN, unidentified = false, hasProfileKey = false)
        )
        assertEquals(
            SEALED_SENDER_DISABLED,
            SealedSender.modeAfter(SEALED_SENDER_UNRESTRICTED, unidentified = false, hasProfileKey = true)
        )
    }

    @Test
    fun `nothing new is not written down`() {
        // A settled answer confirmed again, and a fallback for somebody already known to need
        // one. Writing either would be a database write on every message that says nothing.
        assertNull(SealedSender.modeAfter(SEALED_SENDER_ENABLED, unidentified = true, hasProfileKey = true))
        assertNull(SealedSender.modeAfter(SEALED_SENDER_DISABLED, unidentified = false, hasProfileKey = false))
    }
}
