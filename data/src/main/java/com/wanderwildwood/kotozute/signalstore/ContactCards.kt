package com.wanderwildwood.kotozute.signalstore

import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.VCardVersion
import ezvcard.parameter.EmailType
import ezvcard.parameter.TelephoneType
import ezvcard.property.StructuredName
import org.whispersystems.signalservice.api.messages.shared.SharedContact
import org.whispersystems.signalservice.internal.push.DataMessage

/**
 * Contact cards, between the vCard the phone's contacts speak and the shared contact Signal
 * sends: upstream's `ContactModelMapper`, trimmed to the name, numbers, emails and
 * organisation. Addresses and the avatar are left out; a card is for reaching somebody.
 *
 * A card that arrives is kept as a vCard beside the message, so opening it hands it to the
 * phone's own contacts app, which knows how to add a person; one being sent is read from the
 * vCard the contact picker gives, the same one the SMS side attaches.
 */
object ContactCards {

    /** Whether a data URI's content is a vCard: what the contact picker produces. */
    fun isVCard(dataUri: String): Boolean {
        val type = dataUri.removePrefix("data:").substringBefore(',').substringBefore(';').lowercase()
        return type == "text/x-vcard" || type == "text/vcard"
    }

    /** The name on a vCard data URI, for our own copy of a card we sent. */
    fun nameInDataUri(dataUri: String): String? {
        val comma = dataUri.indexOf(',')
        if (comma < 0) return null
        val text = runCatching {
            String(android.util.Base64.decode(dataUri.substring(comma + 1), android.util.Base64.DEFAULT))
        }.getOrNull() ?: return null
        val card = runCatching { Ezvcard.parse(text).first() }.getOrNull() ?: return null
        return card.formattedName?.value?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(card.structuredName?.given, card.structuredName?.family).joinToString(" ").takeIf { it.isNotBlank() }
    }

    /** The first card in [vcard], as Signal sends it; null when there is no name and nothing to reach. */
    fun toShared(vcard: String): SharedContact? {
        val card = runCatching { Ezvcard.parse(vcard).first() }.getOrNull() ?: return null
        val structured = card.structuredName
        val display = card.formattedName?.value.orEmpty()
        val given = structured?.given ?: display.substringBefore(' ').ifBlank { null }
        val family = structured?.family ?: display.substringAfter(' ', "").ifBlank { null }
        val phones = card.telephoneNumbers.mapNotNull { tel ->
            val value = tel.text ?: tel.uri?.number ?: return@mapNotNull null
            SharedContact.Phone.newBuilder()
                .setValue(value)
                .setType(
                    when {
                        TelephoneType.CELL in tel.types -> SharedContact.Phone.Type.MOBILE
                        TelephoneType.WORK in tel.types -> SharedContact.Phone.Type.WORK
                        TelephoneType.HOME in tel.types -> SharedContact.Phone.Type.HOME
                        else -> SharedContact.Phone.Type.MOBILE
                    }
                )
                .build()
        }
        val emails = card.emails.mapNotNull { e ->
            val value = e.value ?: return@mapNotNull null
            SharedContact.Email.newBuilder()
                .setValue(value)
                .setType(if (EmailType.WORK in e.types) SharedContact.Email.Type.WORK else SharedContact.Email.Type.HOME)
                .build()
        }
        if (given == null && family == null && phones.isEmpty() && emails.isEmpty()) return null
        return SharedContact.newBuilder()
            .setName(
                SharedContact.Name.newBuilder()
                    .setGiven(given)
                    .setFamily(family)
                    .setPrefix(structured?.prefixes?.firstOrNull())
                    .setSuffix(structured?.suffixes?.firstOrNull())
                    .setMiddle(structured?.additionalNames?.firstOrNull())
                    .build()
            )
            .withOrganization(card.organization?.values?.firstOrNull())
            .withPhones(phones)
            .withEmails(emails)
            .build()
    }

    /** The name a received card shows under: its nickname, else given and family, else a number. */
    fun nameOf(contact: DataMessage.Contact): String {
        val name = contact.name
        return name?.nickname?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(name?.givenName, name?.middleName, name?.familyName)
                .filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotBlank() }
            ?: contact.organization?.takeIf { it.isNotBlank() }
            ?: contact.number.firstOrNull()?.value_.orEmpty()
    }

    /** A received card as a vCard, for the phone's contacts app. */
    fun toVCard(contact: DataMessage.Contact): String {
        val card = VCard()
        card.setFormattedName(nameOf(contact))
        contact.name?.let { n ->
            card.structuredName = StructuredName().apply {
                given = n.givenName
                family = n.familyName
                n.prefix?.let { prefixes.add(it) }
                n.suffix?.let { suffixes.add(it) }
                n.middleName?.let { additionalNames.add(it) }
            }
        }
        contact.number.forEach { p ->
            val value = p.value_ ?: return@forEach
            card.addTelephoneNumber(
                value,
                when (p.type) {
                    DataMessage.Contact.Phone.Type.HOME -> TelephoneType.HOME
                    DataMessage.Contact.Phone.Type.WORK -> TelephoneType.WORK
                    else -> TelephoneType.CELL
                }
            )
        }
        contact.email.forEach { e ->
            val value = e.value_ ?: return@forEach
            card.addEmail(value, if (e.type == DataMessage.Contact.Email.Type.WORK) EmailType.WORK else EmailType.HOME)
        }
        contact.organization?.takeIf { it.isNotBlank() }?.let { card.setOrganization(it) }
        return Ezvcard.write(card).version(VCardVersion.V3_0).go()
    }
}
