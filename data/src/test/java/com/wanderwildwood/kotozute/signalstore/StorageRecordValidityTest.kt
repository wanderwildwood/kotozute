package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which contact records from the account's own storage are fit to be stored.
 *
 * Ported from Signal's `ContactRecordProcessor.isInvalid`, which refuses a record whole rather
 * than repairing it -- a record that is wrong about who somebody is cannot be made right by
 * dropping the wrong field, because nothing says which field is the wrong one.
 *
 * ⚠ These exist because on a healthy account this check never fires. The live run that
 * verified this batch reported "0 the account should not have sent", and a check that has
 * never been seen to fire is not evidence of anything.
 */
class StorageRecordValidityTest {

    private val self = SignalStorageService.Self(
        aci = "aci-this-account",
        pni = "PNI:this-account",
        e164 = "+15550000001"
    )

    private fun reason(aci: String? = null, pni: String? = null, e164: String? = null) =
        SignalStorageService.invalidReason(self, aci, pni, e164)

    @Test
    fun `an ordinary contact is fine`() {
        assertNull(reason(aci = "aci-somebody", e164 = "+15550001234"))
        assertNull(reason(pni = "PNI:somebody"))
        // No number at all is not a fault: plenty of people are known by an id and nothing else.
        assertNull(reason(aci = "aci-somebody"))
    }

    @Test
    fun `a record with no id at all is refused`() {
        // "You can't have a contact record without an ACI or PNI" -- upstream's own words.
        assertNotNull(reason(e164 = "+15550001234"))
    }

    @Test
    fun `a record describing this account is refused, by any of its three ids`() {
        // "You can't have a contact record for yourself. That should be an account record."
        // Filed as a contact, the account owner shows up as a stranger in their own picker,
        // and the row carries the account's own number -- which is then a merge key.
        assertNotNull(reason(aci = "aci-this-account"))
        assertNotNull(reason(pni = "PNI:this-account"))
        assertNotNull(reason(aci = "aci-somebody", e164 = "+15550000001"))
    }

    @Test
    fun `somebody else with a similar id is not mistaken for this account`() {
        // The control. If the self test were a prefix or substring match rather than equality
        // it would refuse real contacts, which is a worse fault than the one it prevents.
        assertNull(reason(aci = "aci-this-account-2"))
        assertNull(reason(pni = "PNI:this-account-2"))
        assertNull(reason(aci = "aci-somebody", e164 = "+155500000011"))
    }

    @Test
    fun `a number that is not a number is refused`() {
        // Each of these would otherwise become a row key, and two records carrying the same
        // junk would be taken for one person.
        assertNotNull(reason(aci = "aci-somebody", e164 = "5550001234"))      // no plus
        assertNotNull(reason(aci = "aci-somebody", e164 = "+0550001234"))     // leading zero
        assertNotNull(reason(aci = "aci-somebody", e164 = "+1555"))           // too short
        // The pattern is a leading [1-9] plus six to eighteen more, so nineteen digits is the
        // longest it allows and twenty is the first that is refused.
        assertNull(reason(aci = "aci-somebody", e164 = "+1555000123456789012"))     // 19, allowed
        assertNotNull(reason(aci = "aci-somebody", e164 = "+15550001234567890123")) // 20, refused
        assertNotNull(reason(aci = "aci-somebody", e164 = "+1555CALLNOW"))    // letters
        assertNotNull(reason(aci = "aci-somebody", e164 = "+1 555 000 1234")) // spaces
    }

    @Test
    fun `the number rule is upstream's looser one, not registration's`() {
        // Signal allows up to eighteen digits on a storage record where registration allows
        // fourteen, and deliberately: this is a number another client wrote, possibly years
        // ago, and the job here is to refuse junk rather than re-decide what a number is.
        // Sixteen digits would be refused at registration and is accepted here.
        assertNull(reason(aci = "aci-somebody", e164 = "+1234567890123456"))
    }

    @Test
    fun `a blank number is not treated as a bad one`() {
        // Blank is filtered to null before this is called, and null means "no number", which
        // is ordinary. Refusing it would drop most of a real account.
        assertNull(reason(aci = "aci-somebody", e164 = null))
    }
}
