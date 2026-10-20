package com.wanderwildwood.kotozute.util

import com.wanderwildwood.kotozute.signalstore.ContactCards
import ezvcard.Ezvcard
import ezvcard.parameter.TelephoneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.whispersystems.signalservice.api.messages.shared.SharedContact

/**
 * The card a picked contact becomes, and what Signal makes of it. Made-up people only.
 *
 * The provider's own card cannot be had off a phone, so its shape is copied here from what
 * Android's composer writes: vCard 2.1, with anything outside ASCII in quoted-printable.
 */
class ContactCardFileTest {

    private fun row(mime: String, vararg values: Pair<String, String?>) =
        ContactCardFile.Row(mime, values.toMap())

    private val name = "vnd.android.cursor.item/name"
    private val phone = "vnd.android.cursor.item/phone_v2"
    private val email = "vnd.android.cursor.item/email_v2"
    private val postal = "vnd.android.cursor.item/postal-address_v2"
    private val org = "vnd.android.cursor.item/organization"
    private val event = "vnd.android.cursor.item/contact_event"
    private val note = "vnd.android.cursor.item/note"

    private val zoe = listOf(
        row(name, "data1" to "Zoë Brontë", "data2" to "Zoë", "data3" to "Brontë"),
        row(phone, "data1" to "+1 555 010 4444", "data2" to "2"),
        row(phone, "data1" to "555-010-9876", "data2" to "3"),
        row(phone, "data1" to "(555) 010-1111", "data2" to "1"),
        // The same number again, as a contact joined from two accounts carries it.
        row(phone, "data1" to "+15550104444", "data2" to "2"),
        row(email, "data1" to "zoe@example.org", "data2" to "1"),
        row(postal, "data4" to "12 Example Lane", "data7" to "Springfield", "data9" to "00000", "data2" to "1"),
        row(org, "data1" to "Example Works", "data4" to "Weaver"),
        row(event, "data1" to "1990-05-01", "data2" to "3"),
        row(note, "data1" to "Met at the market 🌱")
    )

    @Test
    fun `a card is written from the rows with everything in them`() {
        val text = ContactCardFile.fromRows("Zoë Brontë", zoe)!!
        val card = Ezvcard.parse(text).first()
        assertEquals("Zoë Brontë", card.formattedName.value)
        assertEquals("Zoë", card.structuredName.given)
        assertEquals("Brontë", card.structuredName.family)
        assertEquals(3, card.telephoneNumbers.size)
        assertTrue(TelephoneType.CELL in card.telephoneNumbers[0].types)
        assertTrue(TelephoneType.WORK in card.telephoneNumbers[1].types)
        assertTrue(TelephoneType.HOME in card.telephoneNumbers[2].types)
        assertEquals("zoe@example.org", card.emails.single().value)
        assertEquals("Springfield", card.addresses.single().locality)
        assertEquals("Example Works", card.organization.values.first())
        assertEquals("Weaver", card.titles.single().value)
        assertNotNull(card.birthday)
        assertEquals("Met at the market 🌱", card.notes.single().value)
        assertTrue(ContactCardFile.usable(text))
        assertEquals("Zoë Brontë", ContactCardFile.nameIn(text))
    }

    @Test
    fun `a birthday without a year is kept, and nonsense is left off`() {
        val noYear = ContactCardFile.fromRows("Kai", listOf(row(phone, "data1" to "5550107777"), row(event, "data1" to "--05-01", "data2" to "3")))!!
        assertTrue(noYear, noYear.lines().any { it.startsWith("BDAY") && it.endsWith("--05-01") })
        val nonsense = ContactCardFile.fromRows("Kai", listOf(row(phone, "data1" to "5550107777"), row(event, "data1" to "next spring", "data2" to "3")))!!
        assertNull(Ezvcard.parse(nonsense).first().birthday)
    }

    @Test
    fun `nothing to send is no card`() {
        assertNull(ContactCardFile.fromRows(null, emptyList()))
        assertNull(ContactCardFile.fromRows("  ", listOf(row(note, "data1" to "just a note"))))
    }

    @Test
    fun `a number with no name is named for the number`() {
        val text = ContactCardFile.fromRows(null, listOf(row(phone, "data1" to "5550108888", "data2" to "2")))!!
        assertEquals("5550108888", ContactCardFile.nameIn(text))
    }

    @Test
    fun `an empty or broken card is not usable`() {
        // What the provider handed back when its composer would not start: nothing at all.
        assertFalse(ContactCardFile.usable(""))
        assertFalse(ContactCardFile.usable("BEGIN:VCARD\r\nVERSION:3.0\r\nEND:VCARD\r\n"))
        assertFalse(ContactCardFile.usable("not a card"))
    }

    @Test
    fun `the provider's own card is usable and names its person`() {
        assertTrue(ContactCardFile.usable(providerCard))
        assertEquals("Zoë Brontë", ContactCardFile.nameIn(providerCard))
    }

    @Test
    fun `file names are the name in plain ASCII`() {
        assertEquals("Zoe_Bronte.vcf", ContactCardFile.fileName("Zoë Brontë"))
        assertEquals("ab_c.vcf", ContactCardFile.fileName("a/b: c?"))
        assertEquals("contact.vcf", ContactCardFile.fileName(" ... "))
        assertEquals("Mira.vcf", ContactCardFile.fileName("Mira 🌿"))
        assertEquals("contact.vcf", ContactCardFile.fileName("山田"))
    }

    @Test
    fun `both names for the card type are a card`() {
        assertTrue(ContactCardFile.isVCardType("text/x-vcard"))
        assertTrue(ContactCardFile.isVCardType("Text/VCard"))
        assertTrue(ContactCardFile.isVCardType("text/vcard; charset=utf-8"))
        assertFalse(ContactCardFile.isVCardType("text/plain"))
        assertFalse(ContactCardFile.isVCardType(null))
    }

    // --- what Signal sends -----------------------------------------------------------

    private fun numbers(shared: SharedContact) = shared.phone.get().map { it.value to it.type }

    @Test
    fun `Signal sends the written card with its name and every number`() {
        val shared = ContactCards.toShared(ContactCardFile.fromRows("Zoë Brontë", zoe)!!)!!
        assertEquals("Zoë", shared.name.given.get())
        assertEquals("Brontë", shared.name.family.get())
        assertEquals(
            listOf(
                "+1 555 010 4444" to SharedContact.Phone.Type.MOBILE,
                "555-010-9876" to SharedContact.Phone.Type.WORK,
                "(555) 010-1111" to SharedContact.Phone.Type.HOME
            ),
            numbers(shared)
        )
        assertEquals("zoe@example.org", shared.email.get().single().value)
        assertEquals("Example Works", shared.organization.get())
    }

    @Test
    fun `Signal sends the provider's card the same`() {
        val shared = ContactCards.toShared(providerCard)!!
        assertEquals("Zoë", shared.name.given.get())
        assertEquals("Brontë", shared.name.family.get())
        assertEquals(
            listOf("+1 555 010 4444" to SharedContact.Phone.Type.MOBILE, "555-010-9876" to SharedContact.Phone.Type.WORK),
            numbers(shared)
        )
    }

    @Test
    fun `an empty card gives Signal nothing to send`() {
        assertNull(ContactCards.toShared(""))
    }

    /** Android's composer output for a made-up contact, quoted-printable and all. */
    private val providerCard = listOf(
        "BEGIN:VCARD",
        "VERSION:2.1",
        "N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Bront=C3=AB;Zo=C3=AB;;;",
        "FN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Zo=C3=AB=20Bront=C3=AB",
        "TEL;CELL:+1 555 010 4444",
        "TEL;WORK:555-010-9876",
        "END:VCARD",
        ""
    ).joinToString("\r\n")
}
