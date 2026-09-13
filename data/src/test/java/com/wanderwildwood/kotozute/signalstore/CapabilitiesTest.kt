package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What this build promises the server it can be sent.
 *
 * A capability is a promise about this *build*, not a fact about the account, and promising
 * one it cannot keep does not close the gap — it moves the failure to the peer who believed
 * it. All six were hardcoded true on the stated grounds that the server refuses the link with
 * `MissingCapability` otherwise; upstream's own linked registration passes false for two of
 * them, which settles that.
 */
class CapabilitiesTest {

    @Test
    fun `a phone number is not optional for this build`() {
        // Signal writes this as a literal false and only a genuinely numberless account flips
        // it. This build cannot be one: linking stores the provisioning message's number as
        // the account's number and dereferences the phone-number fields outright, so an
        // account without one never gets through linking at all. Declaring it told the service
        // this build could do something it would in fact crash on.
        assertFalse(SignalCapabilities.OPTIONAL_PHONE_NUMBER)
    }

    @Test
    fun `linking claims nothing about storage yet`() {
        // Upstream's linked registration passes getCapabilities(false), because at that moment
        // the device has not been told anything about the account. The refresh corrects it.
        assertFalse(SignalCapabilities.forLinking().storage)
    }

    @Test
    fun `the refresh says whether this device really holds the storage key`() {
        // Not a constant either way. Upstream asks hasPin(); the nearest true thing here is
        // whether the storage key is actually held, which is the same question — is the
        // encrypted storage service in use for this account.
        assertTrue(SignalCapabilities.forRefresh(storage = true).storage)
        assertFalse(SignalCapabilities.forRefresh(storage = false).storage)
    }

    @Test
    fun `the four this build genuinely speaks stay true, in both declarations`() {
        // The point of the shared constants: a capability gained in one place and not the
        // other is not a compile error and not a visible fault, just peers quietly not using a
        // message shape this device can read.
        val linking = SignalCapabilities.forLinking()
        val refresh = SignalCapabilities.forRefresh(storage = true)
        for (c in listOf(linking to "linking", refresh to "refresh")) {
            assertTrue(c.second, linking.versionedExpirationTimer)
            assertTrue(c.second, linking.attachmentBackfill)
            assertTrue(c.second, linking.spqr)
            assertTrue(c.second, linking.usernameChangeSyncMessage)
        }
        assertTrue(refresh.versionedExpirationTimer)
        assertTrue(refresh.attachmentBackfill)
        assertTrue(refresh.spqr)
        assertTrue(refresh.usernameChangeSyncMessage)
        // And the one that must agree in both.
        assertFalse(linking.optionalPhoneNumber)
        assertFalse(refresh.optionalPhoneNumber)
    }
}
