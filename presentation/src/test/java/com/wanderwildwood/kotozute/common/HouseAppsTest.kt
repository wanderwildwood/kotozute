package com.wanderwildwood.kotozute.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HouseAppsTest {

    private val all = listOf(HouseApps.MEMO, HouseApps.DATEBOOK, HouseApps.PASSWORDS, HouseApps.WALLET, HouseApps.PICTURES, HouseApps.CONNECTIONS)

    @Test fun everyAppHasItsReleaseCertificate() {
        assertEquals(all.toSet(), HouseApps.RELEASE_CERTS_SHA256.keys)
        for (cert in HouseApps.RELEASE_CERTS_SHA256.values) assertTrue(cert, Regex("[0-9a-f]{64}").matches(cert))
        assertEquals("certificates are told apart", all.size, HouseApps.RELEASE_CERTS_SHA256.values.toSet().size)
    }

    @Test fun onlyTheAppsOwnReleaseCertificateIsTrusted() {
        val other = "00".repeat(32)
        for (pkg in all) {
            val release = HouseApps.RELEASE_CERTS_SHA256.getValue(pkg)
            assertTrue(pkg, HouseApps.trusted(pkg, listOf(release), debuggable = false))
            // An app that took the name with another key, or none that could be read.
            assertFalse(pkg, HouseApps.trusted(pkg, listOf(other), debuggable = false))
            assertFalse(pkg, HouseApps.trusted(pkg, emptyList(), debuggable = false))
            // Signed with the app's key and another as well.
            assertFalse(pkg, HouseApps.trusted(pkg, listOf(release, other), debuggable = false))
        }
    }

    @Test fun oneAppsCertificateDoesNotStandForAnother() {
        val memo = HouseApps.RELEASE_CERTS_SHA256.getValue(HouseApps.MEMO)
        assertFalse(HouseApps.trusted(HouseApps.DATEBOOK, listOf(memo), debuggable = false))
        assertFalse(HouseApps.trusted("com.example.notes", listOf(memo), debuggable = true))
    }

    @Test fun debugKeysOnlyInADebugBuild() {
        val debug = HouseApps.DEBUG_CERTS_SHA256.first()
        assertFalse(HouseApps.trusted(HouseApps.MEMO, listOf(debug), debuggable = false))
        assertTrue(HouseApps.trusted(HouseApps.MEMO, listOf(debug), debuggable = true))
        assertTrue(HouseApps.trusted(HouseApps.DATEBOOK, listOf(debug), debuggable = true))
    }

    @Test fun connectionsIsTrustedOnlyWithItsOwnCertificate() {
        val release = "def146af0a169328cee14b3cb40db62f8d352fadf1e20371dbcf68018c8a5c5b"
        assertEquals("com.wanderwildwood.enishi", HouseApps.CONNECTIONS)
        assertEquals(release, HouseApps.RELEASE_CERTS_SHA256.getValue(HouseApps.CONNECTIONS))
        assertTrue(HouseApps.trusted(HouseApps.CONNECTIONS, listOf(release), debuggable = false))
        assertFalse(HouseApps.trusted(HouseApps.CONNECTIONS, listOf(HouseApps.RELEASE_CERTS_SHA256.getValue(HouseApps.MEMO)), debuggable = false))
    }
}
