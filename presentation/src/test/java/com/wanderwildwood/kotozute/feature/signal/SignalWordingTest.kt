package com.wanderwildwood.kotozute.feature.signal

import com.wanderwildwood.kotozute.repository.GroupNotMade
import com.wanderwildwood.kotozute.repository.SendFailure
import com.wanderwildwood.kotozute.repository.SendRefused
import com.wanderwildwood.kotozute.repository.SignalRepository.ContactCounts
import com.wanderwildwood.kotozute.repository.SignalRepository.ContactsReport
import com.wanderwildwood.kotozute.repository.SignalRepository.LinkFailure
import com.wanderwildwood.kotozute.repository.SignalRepository.LookupRefusal
import com.wanderwildwood.kotozute.repository.SignalRepository.ProfileNameFailure
import com.wanderwildwood.kotozute.repository.SignalRepository.RegistrationFailure
import com.wanderwildwood.kotozute.repository.SignalRepository.StorageRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a person reads when the Signal rail says no.
 *
 * These sentences used to be written in the data layer, where they could only be English. They
 * are string resources now, and the rail hands back a kind; this checks each kind reads, in
 * English, **exactly** what the rail used to say -- every expected string below is the one the
 * rail produced before the move. A wrong resource, an argument in the wrong place, or a space
 * aapt quietly trimmed all show up here as a sentence that no longer matches.
 */
class SignalWordingTest {

    private fun send(failure: SendFailure) = English.of(SignalWording.send(failure))

    // -- the refusals the sender explains ------------------------------------------------------

    @Test
    fun `being asked to prove it is a person says where that can be answered`() {
        val said = send(SendFailure.ProofRequired(0))
        assertEquals(
            "Signal has asked this account to prove it is a person before it will take more " +
                "messages. That has to be answered in Signal on your other phone. Nothing was sent.",
            said
        )
        // This phone cannot answer the challenge -- no push challenge, no captcha screen -- so
        // the sentence has to name somewhere that can rather than imply this one will.
        assertTrue(said, said.contains("your other phone"))
    }

    @Test
    fun `a retry-after is passed on when the server gave one`() {
        assertTrue(send(SendFailure.ProofRequired(600)).endsWith("Nothing was sent. Try again in about 10 minutes."))
        // And is silent when it did not. An invented interval is worse than none.
        assertFalse(send(SendFailure.ProofRequired(0)).contains("Try again"))
    }

    @Test
    fun `rate limiting carries the wait`() {
        assertEquals(
            "Signal is limiting how fast this account can send. Nothing was sent. Try again in about 2 minutes.",
            send(SendFailure.RateLimited(120))
        )
    }

    @Test
    fun `an unlinked device is told so in the same words the socket uses`() {
        assertEquals(
            "This phone is no longer linked to Signal. Link it again to send.",
            send(SendFailure.Unlinked)
        )
    }

    @Test
    fun `the other refusals`() {
        assertEquals(
            "Signal will not accept this version any more. The app needs updating.",
            send(SendFailure.VersionRefused)
        )
        assertEquals(
            "Signal refused this message, and sending it again will not help.",
            send(SendFailure.ServerRejected)
        )
        assertEquals("They are not on Signal any more.", send(SendFailure.TheyLeft))
    }

    @Test
    fun `anything else keeps whatever it said`() {
        assertEquals("the socket closed", send(SendFailure.Unexplained("the socket closed")))
        assertEquals("IOException", send(SendFailure.Unexplained("IOException")))
    }

    @Test
    fun `waits are rounded, never counted down to the second`() {
        fun after(seconds: Long) = English.of(SignalWording.afterWards(seconds))
        assertEquals("", after(0))
        assertEquals("", after(-1))
        assertEquals(" Try again in a minute.", after(30))
        assertEquals(" Try again in a minute.", after(89))
        assertEquals(" Try again in about 2 minutes.", after(90))
        assertEquals(" Try again in about 10 minutes.", after(600))
        assertEquals(" Try again in about an hour.", after(3600))
        assertEquals(" Try again in about 2 hours.", after(7200))
        // Rounded up, not down, on purpose: told to come back too early, somebody tries again
        // into the same refusal. Two hours and one second is "about 3 hours" and that is the
        // right direction to be wrong in.
        assertEquals(" Try again in about 3 hours.", after(7201))
    }

    // -- the results the sender describes ------------------------------------------------------

    @Test
    fun `a failed result names the person, or is worded without a name`() {
        assertEquals("the safety number changed for Ada", send(SendFailure.SafetyNumberChanged("Ada")))
        assertEquals("the safety number changed for this person", send(SendFailure.SafetyNumberChanged(null)))
        assertEquals("Ada is not on Signal any more", send(SendFailure.NotOnSignal("Ada")))
        assertEquals("they are not on Signal any more", send(SendFailure.NotOnSignal(null)))
        assertEquals(
            "Ada has a key this phone cannot use; they may need to reinstall",
            send(SendFailure.KeyUnusable("Ada"))
        )
        assertEquals(
            "they have a key this phone cannot use; they may need to reinstall",
            send(SendFailure.KeyUnusable(null))
        )
        assertEquals("the phone could not reach Signal", send(SendFailure.Unreachable))
        assertEquals("sending too fast; try again in 2 minutes", send(SendFailure.TooFast(120_000L)))
        assertEquals("sending too fast; try again shortly", send(SendFailure.TooFast(null)))
        assertEquals(
            "Signal wants this phone to prove it is a person, which it cannot do yet",
            send(SendFailure.ProofNeeded)
        )
        assertEquals("it did not send, and the server did not say why", send(SendFailure.ServerSilent))
    }

    @Test
    fun `the sender's own refusals`() {
        assertEquals("that message is no longer held", send(SendFailure.NoLongerHeld))
        assertEquals(
            "the group has no members this device can reach",
            send(SendFailure.NoReachableMembers)
        )
        assertEquals("could not reach any of the 7 group members", send(SendFailure.NobodyReached(7)))
        assertEquals("the primary refused the contacts request", send(SendFailure.PrimaryRefusedRequest))
        assertEquals(
            "That message is too long to send. Signal takes about 2,000 characters in one " +
                "message; sending it in two will work.",
            send(SendFailure.TooLong)
        )
        assertEquals(
            "could not prepare the attachment: no such file",
            send(SendFailure.AttachmentUnprepared("no such file"))
        )
        // `${t.message}` of an exception with none, which is what the sender always wrote.
        assertEquals("could not prepare the attachment: null", send(SendFailure.AttachmentUnprepared("null")))
        assertEquals(
            "This phone's sending keys are out of date and it could not replace them just now, " +
                "so nothing was sent. It will keep trying.",
            send(SendFailure.KeysStale)
        )
        assertEquals(
            "This phone has no sealed sending certificate at the moment, so nothing was sent. " +
                "It will try again on its own.",
            send(SendFailure.NoCertificate)
        )
    }

    @Test
    fun `a group that cannot be sent to says which way`() {
        assertEquals("You are not in this group any more, so nothing was sent.", send(SendFailure.NotInGroup))
        assertEquals("This group has ended, so nothing was sent.", send(SendFailure.GroupEnded))
        assertEquals(
            "This phone could not reach the group just now, so nothing was sent. " +
                "It will work when the connection is back.",
            send(SendFailure.GroupUnreachable)
        )
        assertEquals(
            "Only this group's admins can post in it. Your message was not sent.",
            send(SendFailure.AdminsOnly)
        )
        assertEquals(
            "This conversation has only a phone number, not a Signal address. " +
                "Write to them from a new message instead.",
            send(SendFailure.NumberOnly)
        )
        assertEquals(
            "sending attachments to a group is not supported yet",
            send(SendFailure.AttachmentsToGroup)
        )
        assertEquals("no group key on this thread yet", send(SendFailure.NoGroupKey))
    }

    @Test
    fun `only the rail's own kinds are worded, and anything else keeps its message`() {
        assertEquals(
            "This group has ended, so nothing was sent.",
            SignalWording.failure(SendRefused(SendFailure.GroupEnded))?.let(English::of)
        )
        assertEquals(
            "A group needs somebody else in it.",
            SignalWording.failure(GroupNotMade(GroupNotMade.Why.NOBODY_ELSE))?.let(English::of)
        )
        assertNull(SignalWording.failure(IllegalStateException("something else")))
    }

    // -- making a group ------------------------------------------------------------------------

    @Test
    fun `every way a group is not made`() {
        fun group(why: GroupNotMade.Why) = English.of(SignalWording.group(why))
        assertEquals("This phone is not linked to an account.", group(GroupNotMade.Why.NOT_LINKED))
        assertEquals(
            "This account has no profile on file, and a group cannot be made without one.",
            group(GroupNotMade.Why.NO_PROFILE)
        )
        assertEquals("A group needs somebody else in it.", group(GroupNotMade.Why.NOBODY_ELSE))
        assertEquals("The group could not be put together.", group(GroupNotMade.Why.NOT_PUT_TOGETHER))
        assertEquals("The server would not authorize this account.", group(GroupNotMade.Why.NOT_AUTHORIZED))
        assertEquals("The server would not take the new group.", group(GroupNotMade.Why.SERVER_REFUSED))
        assertEquals(
            "The group was made but this phone cannot address it.",
            group(GroupNotMade.Why.UNADDRESSABLE)
        )
    }

    // -- registering, linking, naming ----------------------------------------------------------

    @Test
    fun `every way a registration step fails`() {
        fun say(failure: RegistrationFailure) = English.of(SignalWording.registration(failure))
        assertEquals("that is not a phone number this can register", say(RegistrationFailure.NotANumber))
        assertEquals("the server will not send a code yet", say(RegistrationFailure.NoCodeYet))
        assertEquals("could not start registration: E", say(RegistrationFailure.CouldNotStart("E")))
        assertEquals(
            "the captcha was accepted but the server still will not send a code",
            say(RegistrationFailure.CaptchaAcceptedNoCode)
        )
        assertEquals("the captcha was not accepted: E", say(RegistrationFailure.CaptchaRefused("E")))
        assertEquals("could not send a code: E", say(RegistrationFailure.CodeNotSent("E")))
        assertEquals("that code was not accepted: E", say(RegistrationFailure.CodeRefused("E")))
        assertEquals("that code was not accepted", say(RegistrationFailure.CodeRefused(null)))
        assertEquals(
            "could not generate this account's key material",
            say(RegistrationFailure.NoKeyMaterial)
        )
        assertEquals(
            "Signal kept this number locked even though its PIN was right, with about 6 days left " +
                "to run. Link this phone to the account instead.",
            say(RegistrationFailure.Locked(6))
        )
        assertEquals(
            "Signal kept this number locked even though its PIN was right, with about a day left " +
                "to run. Link this phone to the account instead.",
            say(RegistrationFailure.Locked(1))
        )
        assertEquals(
            "Signal kept this number locked even though its PIN was right. Link this phone to the " +
                "account instead.",
            say(RegistrationFailure.Locked(0))
        )
        assertEquals(
            "Signal has no PIN for this number, so the registration lock has to run out first. " +
                "Link this phone to the account instead.",
            say(RegistrationFailure.PinDataMissing(6))
        )
        assertEquals("the PIN could not be checked: E", say(RegistrationFailure.PinCheckFailed("E")))
        assertEquals("registration refused: E", say(RegistrationFailure.Refused("E")))
        assertEquals("unexpected registration state", say(RegistrationFailure.Unexpected))
        assertEquals("IOException", say(RegistrationFailure.Unexplained("IOException")))
    }

    @Test
    fun `every way linking fails`() {
        fun say(failure: LinkFailure) = English.of(SignalWording.link(failure))
        assertEquals("nobody scanned the code in time; ask for a new one", say(LinkFailure.NotScanned))
        assertEquals("the provisioning message could not be decrypted", say(LinkFailure.Undecryptable))
        assertEquals("no provisioning code in the message", say(LinkFailure.NoProvisioningCode))
        assertEquals("registration refused: E", say(LinkFailure.Refused("E")))
        assertEquals("IOException", say(LinkFailure.Unexplained("IOException")))
    }

    @Test
    fun `every way the account's name does not save`() {
        fun say(failure: ProfileNameFailure) = English.of(SignalWording.profileName(failure))
        assertEquals("this phone has no account yet", say(ProfileNameFailure.NoAccount))
        assertEquals("this account has no service id yet", say(ProfileNameFailure.NoServiceId))
        assertEquals("this account has no profile key", say(ProfileNameFailure.NoProfileKey))
        assertEquals("this account's profile key will not load", say(ProfileNameFailure.ProfileKeyUnreadable))
        assertEquals("a profile needs a given name", say(ProfileNameFailure.NoGivenName))
        assertEquals("the server would not take the name: E", say(ProfileNameFailure.Refused("E")))
        assertEquals("IOException", say(ProfileNameFailure.Unexplained("IOException")))
    }

    // -- contacts ------------------------------------------------------------------------------

    private fun contacts(report: ContactsReport) = English.of(SignalWording.contacts(report))

    @Test
    fun `a fetch says every count, and every skipped kind`() {
        assertEquals("71 contact(s) from 71 record(s)", contacts(ContactsReport.Read(71, 71)))
        assertEquals(
            "71 contact(s) from 201 record(s) · 3 by phone number · skipped 4 the service would " +
                "not hand over, 5 would not open, 6 not a contact, 7 with no address at all, " +
                "2 with a number",
            contacts(
                ContactsReport.Read(
                    contacts = 71, records = 201, pniOnly = 3, unreadable = 4, unopened = 5,
                    notContacts = 6, anonymous = 7, anonymousWithNumber = 2
                )
            )
        )
        assertEquals(
            "1 contact(s) from 2 record(s) · skipped 1 with no address at all",
            contacts(ContactsReport.Read(contacts = 1, records = 2, anonymous = 1))
        )
    }

    @Test
    fun `a fetch that could not happen says why`() {
        assertEquals("This phone is not linked to Signal yet", contacts(ContactsReport.NotLinked))
        assertEquals(
            "Asked Signal for the contact list. It arrives in a moment, if your Signal answers",
            contacts(ContactsReport.Requested)
        )
        assertEquals("the storage key is not here yet", contacts(ContactsReport.ReadRefused(StorageRefusal.NO_KEY)))
        assertEquals(
            "the service would not give an auth token",
            contacts(ContactsReport.ReadRefused(StorageRefusal.NO_AUTH))
        )
        assertEquals(
            "this account has no stored records yet",
            contacts(ContactsReport.ReadRefused(StorageRefusal.NOTHING_STORED))
        )
        assertEquals(
            "the manifest could not be read",
            contacts(ContactsReport.ReadRefused(StorageRefusal.MANIFEST_UNREADABLE))
        )
        assertEquals(
            "the manifest would not open with this key",
            contacts(ContactsReport.ReadRefused(StorageRefusal.WRONG_KEY))
        )
    }

    @Test
    fun `a lookup says what it found, and why it did not look`() {
        assertEquals("4 on Signal, from 9 number(s)", contacts(ContactsReport.Discovered(4, 9, 0)))
        assertEquals(
            "4 on Signal, from 9 number(s) · 3 known by phone number only",
            contacts(ContactsReport.Discovered(4, 9, 3))
        )
        assertEquals("Every number has been looked up already", contacts(ContactsReport.AllLookedUp))
        assertEquals(
            "There are no phone numbers in this phone's contacts to look up",
            contacts(ContactsReport.NoNumbers)
        )
        fun refused(why: LookupRefusal, minutes: Long = 0) = contacts(ContactsReport.LookupRefused(why, minutes))
        assertEquals("There are no numbers to look up", refused(LookupRefusal.NO_VALID_NUMBERS))
        assertEquals(
            "Signal will not answer more lookups for about 12 more minute(s)",
            refused(LookupRefusal.BLOCKED, 12)
        )
        assertEquals("too many new numbers to look up at once", refused(LookupRefusal.TOO_MANY))
        assertEquals(
            "could not read this phone's own records to ask with",
            refused(LookupRefusal.OWN_RECORDS_UNREADABLE)
        )
        assertEquals(
            "Signal will not answer more lookups for now. That is a limit on the account, " +
                "not on this phone, and it lifts on its own.",
            refused(LookupRefusal.EXHAUSTED)
        )
        assertEquals(
            "The record of what was looked up before is out of step. Try once more.",
            refused(LookupRefusal.TOKEN_OUT_OF_STEP)
        )
        assertEquals("Signal refused the list of numbers", refused(LookupRefusal.NUMBERS_REFUSED))
        assertEquals("The lookup did not finish", refused(LookupRefusal.UNFINISHED))
    }

    @Test
    fun `the contact counts on the connection line`() {
        fun counts(c: ContactCounts) = English.of(SignalWording.contactCounts(c))
        assertEquals(
            "10 contact(s), 8 with a profile key, 6 named",
            counts(ContactCounts(known = 10, withProfileKey = 8, named = 6, withUsername = 0, nameless = 0))
        )
        assertEquals(
            "10 contact(s), 8 with a profile key, 6 named, 2 by username, 1 with nothing to show but an id",
            counts(ContactCounts(known = 10, withProfileKey = 8, named = 6, withUsername = 2, nameless = 1))
        )
    }
}
