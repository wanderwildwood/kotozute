package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.whispersystems.signalservice.internal.storage.protos.ContactRecord
import org.whispersystems.signalservice.internal.storage.protos.StorageRecord

/**
 * Who is offered when a Signal conversation is started: Signal's SIGNAL_CONTACT.
 *
 * The case that matters is the stranger from a group -- a row with a profile name and no
 * record, which the column default calls whitelisted. That person was listed as a contact.
 */
class ContactOfferTest {

    private val aci = "11111111-1111-4111-8111-111111111111"

    private fun record(contact: ContactRecord) = StorageRecord(contact = contact).encode()

    @Test
    fun `somebody met only in a group is not offered, whatever the column default says`() {
        assertFalse(ContactOffer.offered(systemContact = false, storageRecord = null, whitelisted = true))
    }

    @Test
    fun `somebody the contacts sync named is offered`() {
        assertTrue(ContactOffer.offered(systemContact = true, storageRecord = null, whitelisted = false))
    }

    @Test
    fun `somebody the account shares its profile with is offered`() {
        val raw = record(ContactRecord(aci = aci, givenName = "Ada", whitelisted = true))
        assertTrue(ContactOffer.offered(systemContact = false, storageRecord = raw, whitelisted = true))
    }

    @Test
    fun `somebody in the primary's address book is offered`() {
        val raw = record(ContactRecord(aci = aci, systemGivenName = "Tomas", whitelisted = false))
        assertTrue(ContactOffer.offered(systemContact = false, storageRecord = raw, whitelisted = false))
    }

    @Test
    fun `a record with neither a system name nor sharing is not offered`() {
        val raw = record(ContactRecord(aci = aci, givenName = "Ada", systemGivenName = "", whitelisted = false))
        assertFalse(ContactOffer.offered(systemContact = false, storageRecord = raw, whitelisted = true))
    }

    @Test
    fun `an unreadable record falls back on the column written from it`() {
        val junk = byteArrayOf(0x7f, 0x7f, 0x7f)
        assertTrue(ContactOffer.offered(systemContact = false, storageRecord = junk, whitelisted = true))
        assertFalse(ContactOffer.offered(systemContact = false, storageRecord = junk, whitelisted = false))
    }
}
