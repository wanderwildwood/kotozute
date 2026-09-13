package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a block actually catches the person it names.
 *
 * The account can hold a block against a phone number with no account id at all — that is what
 * `blocked.blockedE164s` carries — and the receive path tested the *envelope's* sourceE164,
 * which a modern server does not fill. So a block by number matched nothing: the sender was
 * decrypted, filed, shown, notified, and answered with a delivery receipt confirming to them
 * that this phone is on and had received them.
 *
 * Signal never faces this because blocked is a column on the recipient row, which already
 * unifies the account id, the phone-number identity and the number; its receive path asks the
 * resolved sender and never the envelope.
 */
class BlockedByNumberTest {

    private val byNumberOnly = SignalBlockStore.Blocked(aci = null, e164 = "+15550001234", blockedAt = 1L)
    private val byAciOnly = SignalBlockStore.Blocked(aci = "aci-blocked", e164 = null, blockedAt = 1L)
    private val list = listOf(byNumberOnly, byAciOnly)

    @Test
    fun `a block held by number catches a sender whose number we resolved`() {
        // The fault, as one assertion. The sender arrives with an account id and no number of
        // their own; the number comes from the recipient row.
        assertTrue(SignalBlockStore.matches(list, serviceId = "aci-someone", e164 = "+15550001234"))
    }

    @Test
    fun `and does not catch them when no number could be resolved`() {
        // Honest about the limit: with nothing to match on, an e164-only block cannot fire.
        // This is why the receive path resolves the number rather than trusting the envelope.
        assertFalse(SignalBlockStore.matches(list, serviceId = "aci-someone", e164 = null))
    }

    @Test
    fun `a block held by account id still catches them`() {
        assertTrue(SignalBlockStore.matches(list, serviceId = "aci-blocked", e164 = null))
        assertTrue(SignalBlockStore.matches(list, serviceId = "aci-blocked", e164 = "+15559999999"))
    }

    @Test
    fun `somebody not blocked gets through`() {
        // The control. A test that blocks everybody would pass the ones above and be useless.
        assertFalse(SignalBlockStore.matches(list, serviceId = "aci-someone", e164 = "+15559999999"))
    }

    @Test
    fun `an empty list blocks nobody`() {
        assertFalse(SignalBlockStore.matches(emptyList(), "aci-anyone", "+15550001234"))
    }

    @Test
    fun `a sender with neither name is not blocked`() {
        // Not "blocked by default": with nothing to compare, the answer is no.
        assertFalse(SignalBlockStore.matches(list, serviceId = null, e164 = null))
        assertFalse(SignalBlockStore.matches(list, serviceId = "", e164 = "   "))
    }

    @Test
    fun `matching ignores case, as the store's own comparison does`() {
        assertTrue(SignalBlockStore.matches(list, serviceId = "ACI-BLOCKED", e164 = null))
    }
}
