package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The trust levels a safety number is reported through.
 *
 * Small, but the mapping is the difference between "their key changed, do not send" and
 * "everything is fine", and it is expressed as integers on one side and strings on the other.
 * Getting it backwards would show a reassuring message on exactly the occasion that matters.
 */
class SafetyNumberTrustTest {

    private fun level(stored: Int): String = when (stored) {
        0 -> "UNTRUSTED"
        2 -> "TRUSTED_VERIFIED"
        else -> "TRUSTED_UNVERIFIED"
    }

    @Test
    fun `a changed key reads as untrusted`() {
        assertEquals("UNTRUSTED", level(SignalIdentityKeyStore.UNTRUSTED))
    }

    @Test
    fun `an accepted key reads as trusted but unverified`() {
        assertEquals("TRUSTED_UNVERIFIED", level(SignalIdentityKeyStore.TRUSTED_UNVERIFIED))
    }

    @Test
    fun `a verified key reads as verified`() {
        assertEquals("TRUSTED_VERIFIED", level(SignalIdentityKeyStore.TRUSTED_VERIFIED))
    }

    /**
     * Accepting records TRUSTED_UNVERIFIED, never TRUSTED_VERIFIED. Someone choosing to
     * proceed is not the same as someone having compared the digits, and the app must not
     * claim the stronger of the two on their behalf.
     */
    @Test
    fun `accepting does not claim verification`() {
        assertEquals("TRUSTED_UNVERIFIED", level(SignalIdentityKeyStore.TRUSTED_UNVERIFIED))
        assert(SignalIdentityKeyStore.TRUSTED_UNVERIFIED != SignalIdentityKeyStore.TRUSTED_VERIFIED)
    }
}
