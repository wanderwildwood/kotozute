package com.wanderwildwood.kotozute.signalstore

import org.whispersystems.signalservice.internal.storage.protos.StorageRecord

/**
 * Whether somebody is offered when a Signal conversation is started.
 *
 * Signal's `RecipientTable.SIGNAL_CONTACT`: a system contact, or somebody the account shares
 * its profile with. Everybody else this phone has heard of -- every member of every group, to
 * begin with -- is somebody seen, not somebody known, and listing them read as a contact list
 * full of strangers.
 *
 * Who has a one-to-one conversation already is not asked here; the repository adds them from
 * the threads, as Signal's profile sharing would for anyone actually written to.
 */
internal object ContactOffer {

    /**
     * [systemContact] is a contacts sync naming them. [storageRecord] is the account's own
     * record for them, where a storage read has kept one: its system name is the address book
     * as the primary knows it, and its `whitelisted` is profile sharing.
     *
     * [whitelisted] is the column, and counts only beside a record. Its default is 1 for every
     * row, so on a row the account has never described it says nothing.
     */
    fun offered(systemContact: Boolean, storageRecord: ByteArray?, whitelisted: Boolean): Boolean {
        if (systemContact) return true
        val record = storageRecord ?: return false
        val contact = runCatching { StorageRecord.ADAPTER.decode(record).contact }.getOrNull()
            // A record kept but unreadable: the column was written from it when it was read.
            ?: return whitelisted
        return contact.whitelisted ||
            !ProfileNames.joined(contact.systemGivenName, contact.systemFamilyName).isNullOrBlank()
    }
}
