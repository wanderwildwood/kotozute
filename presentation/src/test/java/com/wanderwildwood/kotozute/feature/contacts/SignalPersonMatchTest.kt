package com.wanderwildwood.kotozute.feature.contacts

import com.wanderwildwood.kotozute.extensions.removeAccents
import com.wanderwildwood.kotozute.feature.compose.editing.ComposeItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding someone on Signal in the new-message list.
 *
 * The name is the usual case. The number matters more here than it does for an address-book
 * contact: a person the contacts sync knows only as a number has that number for a name, and
 * it is stored as E.164 while nobody types it that way.
 */
class SignalPersonMatchTest {

    private fun person(name: String, number: String = "") =
        ComposeItem.SignalPerson("direct:11111111-2222-4333-8444-555555555555", name, number)

    private fun matched(person: ComposeItem.SignalPerson, query: String) =
        matches(person, query, query.removeAccents())

    @Test
    fun `part of a name is enough`() {
        assertTrue(matched(person("Alice Smith"), "ali"))
        assertTrue(matched(person("Alice Smith"), "smi"))
    }

    @Test
    fun `case does not matter`() {
        assertTrue(matched(person("Alice Smith"), "ALICE"))
    }

    @Test
    fun `an accent typed or not typed still finds them`() {
        assertTrue(matched(person("Zoë"), "zoe"))
        assertTrue(matched(person("Zoë"), "zoë"))
    }

    @Test
    fun `a number is matched by its digits, however it was written`() {
        val alice = person("Alice", "+15550001234")
        assertTrue(matched(alice, "555 000 1234"))
        assertTrue(matched(alice, "(555) 0001234"))
        assertTrue(matched(alice, "5550001234"))
    }

    @Test
    fun `a name that is a number is found by typing the number`() {
        assertTrue(matched(person("+15550001234", "+15550001234"), "5550001"))
    }

    @Test
    fun `somebody else is not found`() {
        assertFalse(matched(person("Alice", "+15550001234"), "bruno"))
        assertFalse(matched(person("Alice", "+15550001234"), "5559999"))
    }

    @Test
    fun `a name with no digits is not matched by a digit query`() {
        // The control for the case above: if this passed, the digit rule would be matching
        // everything rather than matching numbers.
        assertFalse(matched(person("Alice"), "555"))
    }
}
