package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a message carries this account's profile key.
 *
 * Signal attaches one only where the account shares its profile with the recipient
 * (`PushSendJob.getProfileKey`: `isSystemContact || isProfileSharing`). Upstream never has to
 * decide what a *failed* read means, because it does not swallow one. This app does swallow --
 * a send must not crash on a bad database read -- so the direction it swallows in is a
 * decision, and this is that decision written down.
 */
class ProfileKeySharingTest {

    @Test
    fun `an account that shares its profile gets the key`() {
        assertTrue(SignalSender.sharesProfile { true })
    }

    @Test
    fun `an explicit no withholds it`() {
        assertFalse(SignalSender.sharesProfile { false })
    }

    /**
     * The case this exists for. A read that throws is not an answer, and it used to be read
     * as "yes": the key went to somebody the account had said not to share with, and they
     * keep it.
     */
    @Test
    fun `a read that fails withholds it rather than sharing`() {
        assertFalse(SignalSender.sharesProfile { error("the contact store would not open") })
    }
}
