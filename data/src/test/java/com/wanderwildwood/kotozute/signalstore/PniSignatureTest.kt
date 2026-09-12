package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair

/**
 * What a PNI signature actually proves.
 *
 * A person's phone-number identity signs itself with their account identity, and verifying
 * that says the two keys belong to the same someone. It is the only claim worth acting on:
 * discovery hands a linked device a PNI and nothing else, so without proof the pairing would
 * have to be taken on an assertion -- and an assertion would let anybody name somebody else's
 * phone-number identity and take over the conversation held under it.
 *
 * The real path lives in `SignalReceiver.rememberVerifiedPni`, which cannot be exercised here
 * (it needs an envelope, a cipher and the protocol store). This pins the property that path
 * depends on, against the actual library rather than a description of it.
 */
class PniSignatureTest {

    @Test
    fun `a phone-number identity signed by its own account verifies`() {
        val aci = IdentityKeyPair.generate()
        val pni = IdentityKeyPair.generate()

        val signature = pni.signAlternateIdentity(aci.publicKey)

        assertTrue(pni.publicKey.verifyAlternateIdentity(aci.publicKey, signature))
    }

    @Test
    fun `a signature from somebody else's account does not verify`() {
        // The attack the check exists for: a stranger asserting that a PNI belonging to
        // someone else is theirs, to capture the conversation held under it.
        val realAci = IdentityKeyPair.generate()
        val strangerAci = IdentityKeyPair.generate()
        val pni = IdentityKeyPair.generate()

        val signature = pni.signAlternateIdentity(strangerAci.publicKey)

        assertFalse(pni.publicKey.verifyAlternateIdentity(realAci.publicKey, signature))
    }

    @Test
    fun `a signature over a different phone-number identity does not verify`() {
        val aci = IdentityKeyPair.generate()
        val pni = IdentityKeyPair.generate()
        val otherPni = IdentityKeyPair.generate()

        val signature = otherPni.signAlternateIdentity(aci.publicKey)

        assertFalse(pni.publicKey.verifyAlternateIdentity(aci.publicKey, signature))
    }
}
