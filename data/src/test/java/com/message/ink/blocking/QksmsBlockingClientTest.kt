package com.message.ink.blocking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QksmsBlockingClientTest {

    @Test
    fun blacklistedNumbersAreBlocked() {
        assertTrue(
            shouldBlockMessage(
                isBlacklisted = true,
                onlyAllowContacts = false,
                canReadContacts = true,
                isContact = false,
            )
        )
    }

    @Test
    fun strangersArriveNormallyWhenTheRuleIsOff() {
        assertFalse(
            shouldBlockMessage(
                isBlacklisted = false,
                onlyAllowContacts = false,
                canReadContacts = true,
                isContact = false,
            )
        )
    }

    @Test
    fun strangersAreBlockedWhenTheRuleIsOn() {
        assertTrue(
            shouldBlockMessage(
                isBlacklisted = false,
                onlyAllowContacts = true,
                canReadContacts = true,
                isContact = false,
            )
        )
    }

    @Test
    fun contactsStillGetThroughWhenTheRuleIsOn() {
        assertFalse(
            shouldBlockMessage(
                isBlacklisted = false,
                onlyAllowContacts = true,
                canReadContacts = true,
                isContact = true,
            )
        )
    }

    /**
     * The one that matters. Without the contacts permission every lookup comes back "not a
     * contact", so a rule that trusted it alone would block every incoming message - including
     * from the people the phone's owner actually talks to - and it would look like the network
     * had gone quiet rather than like a setting had misfired.
     */
    @Test
    fun nothingIsBlockedWhenContactsCannotBeRead() {
        assertFalse(
            shouldBlockMessage(
                isBlacklisted = false,
                onlyAllowContacts = true,
                canReadContacts = false,
                isContact = false,
            )
        )
    }

    @Test
    fun blacklistStillAppliesWhenContactsCannotBeRead() {
        assertTrue(
            shouldBlockMessage(
                isBlacklisted = true,
                onlyAllowContacts = true,
                canReadContacts = false,
                isContact = false,
            )
        )
    }
}
