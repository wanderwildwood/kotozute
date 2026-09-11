/*
 * Copyright (C) 2019 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.wanderwildwood.kotozute.feature.compose.editing

import com.wanderwildwood.kotozute.model.Contact
import com.wanderwildwood.kotozute.model.ContactGroup
import com.wanderwildwood.kotozute.model.Conversation
import com.wanderwildwood.kotozute.model.PhoneNumber
import io.realm.RealmList

sealed class ComposeItem {

    abstract fun getContacts(): List<Contact>

    data class New(val value: Contact) : ComposeItem() {
        override fun getContacts(): List<Contact> = listOf(value)
    }

    data class Recent(val value: Conversation) : ComposeItem() {
        override fun getContacts(): List<Contact> = value.recipients.map { recipient ->
            recipient.contact ?: Contact(numbers = RealmList(PhoneNumber(address = recipient.address)))
        }
    }

    data class Starred(val value: Contact) : ComposeItem() {
        override fun getContacts(): List<Contact> = listOf(value)
    }

    data class Group(val value: ContactGroup) : ComposeItem() {
        override fun getContacts(): List<Contact> = value.contacts
    }

    data class Person(val value: Contact) : ComposeItem() {
        override fun getContacts(): List<Contact> = listOf(value)
    }

    /**
     * Someone reachable on Signal. Not a contact: this composer sends SMS, and a Signal
     * person is not a recipient it can hold. Choosing one opens the conversation with them
     * on the other rail instead, which is why [getContacts] is empty rather than a Contact
     * carrying their number -- an SMS to the same number is a different message to a
     * different place, and the picker must not quietly send one for the other.
     */
    /**
     * The line that says the rest of the list is a different question.
     *
     * Without it the Signal entries sit after the whole address book, which on a phone with a
     * few hundred contacts is the same as not being there -- somebody opened the screen, saw
     * their SMS contacts, and reported the feature as not working. It was working, several
     * screens down.
     */
    object SignalHeader : ComposeItem() {
        override fun getContacts(): List<Contact> = emptyList()
    }

    data class SignalPerson(
        val threadKey: String,
        val name: String,
        val number: String
    ) : ComposeItem() {
        override fun getContacts(): List<Contact> = emptyList()
    }
}
