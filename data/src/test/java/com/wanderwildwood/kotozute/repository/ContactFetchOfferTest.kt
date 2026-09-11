package com.wanderwildwood.kotozute.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When to offer, once, to read the account's contact list.
 *
 * A one-off prompt has one way to fail badly: appearing for somebody who did not need it.
 * Every case here is a phone that must not be asked.
 */
class ContactFetchOfferTest {

    private fun offer(
        linked: Boolean = true,
        key: Boolean = false,
        names: Boolean = false
    ) = SignalDirectory.shouldOfferContactFetch(linked, key, names)

    @Test
    fun `a freshly linked phone with no names is exactly the case`() {
        assertTrue(offer())
    }

    @Test
    fun `a phone that has already read the list is not asked`() {
        assertFalse(offer(key = true))
    }

    @Test
    fun `a phone whose primary answered the ordinary sync is not asked`() {
        assertFalse(offer(names = true))
    }

    @Test
    fun `a phone with no Signal of its own is not asked`() {
        assertFalse(offer(linked = false))
    }

    @Test
    fun `a bridge that has names and a key is not asked twice over`() {
        assertFalse(offer(linked = false, key = true, names = true))
    }
}
