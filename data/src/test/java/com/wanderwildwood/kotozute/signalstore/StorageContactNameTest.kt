package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.whispersystems.signalservice.internal.storage.protos.ContactRecord

/**
 * What to call somebody whose record came out of the storage service.
 *
 * The same rule as everywhere else here: the name from the reader's own address book first,
 * the name the person publishes second. Getting the order wrong renames half of somebody's
 * conversations the first time this runs — silently, and in the direction of whatever
 * strangers call themselves.
 */
class StorageContactNameTest {

    private fun record(
        systemGiven: String = "", systemFamily: String = "",
        given: String = "", family: String = ""
    ) = ContactRecord(
        systemGivenName = systemGiven, systemFamilyName = systemFamily,
        givenName = given, familyName = family
    )

    @Test
    fun `the address book wins`() {
        assertEquals(
            "Ada Lovelace",
            SignalStorageService.nameOf(
                record(systemGiven = "Ada", systemFamily = "Lovelace", given = "A", family = "L")
            )
        )
    }

    @Test
    fun `the published name stands in where the address book has none`() {
        assertEquals("Ada L", SignalStorageService.nameOf(record(given = "Ada", family = "L")))
    }

    @Test
    fun `one name is a name`() {
        assertEquals("Ada", SignalStorageService.nameOf(record(systemGiven = "Ada")))
        assertEquals("Grace", SignalStorageService.nameOf(record(given = "Grace")))
    }

    @Test
    fun `nobody named is null, not an empty name`() {
        // Null leaves whatever the contact store already had; an empty string would overwrite
        // a good name with nothing.
        assertNull(SignalStorageService.nameOf(record()))
    }
}
