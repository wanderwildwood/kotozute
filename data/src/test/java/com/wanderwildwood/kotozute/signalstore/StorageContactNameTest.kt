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
        given: String = "", family: String = "",
        nicknameGiven: String? = null, nicknameFamily: String? = null
    ) = ContactRecord(
        systemGivenName = systemGiven, systemFamilyName = systemFamily,
        givenName = given, familyName = family,
        nickname = if (nicknameGiven == null && nicknameFamily == null) null
        else ContactRecord.Name(given = nicknameGiven.orEmpty(), family = nicknameFamily.orEmpty())
    )

    @Test
    fun `a nickname the account owner typed beats everything`() {
        // Signal's own order. This is the name they chose for this person; showing the address
        // book or the profile over it renames somebody they deliberately renamed.
        assertEquals(
            "Gran",
            SignalStorageService.nameOf(
                record(
                    systemGiven = "Ada", systemFamily = "Lovelace",
                    given = "A", family = "L",
                    nicknameGiven = "Gran"
                )
            )
        )
    }

    @Test
    fun `a nickname names somebody who has no other name at all`() {
        // The case that leaves a row showing eight characters of a service id.
        assertEquals(
            "Gran",
            SignalStorageService.nameOf(record(nicknameGiven = "Gran"))
        )
    }

    @Test
    fun `an empty nickname is not a name`() {
        // The field is present but blank -- which must fall through, not win with "".
        assertEquals(
            "Ada Lovelace",
            SignalStorageService.nameOf(
                record(systemGiven = "Ada", systemFamily = "Lovelace", nicknameGiven = "")
            )
        )
    }

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
