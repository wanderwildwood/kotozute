package com.wanderwildwood.kotozute.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The list of people a Signal conversation can be started with.
 *
 * Worth testing away from the device because the two sources disagree in ways that only show
 * up on somebody else's account: a bridge user has a thread for everyone and no contact
 * store, a linked user has a contact store and threads only for conversations that have
 * happened, and the names are missing on different sides in each case.
 */
class SignalDirectoryTest {

    private val self = "00000000-0000-4000-8000-000000005e1f"
    private val alice = "11111111-2222-4333-8444-555555555555"
    private val bruno = "22222222-3333-4444-8555-666666666666"

    private fun row(uuid: String, name: String = "", number: String = "") =
        SignalDirectory.Row(uuid, name, number)

    @Test
    fun `someone known only to the contacts sync is still someone to write to`() {
        val people = SignalDirectory.merge(
            threads = listOf(),
            contacts = listOf(row(alice, name = "Alice", number = "+15550001")),
            selfAci = self
        )

        assertEquals(1, people.size)
        assertEquals("direct:$alice", people[0].threadKey)
        assertEquals("Alice", people[0].name)
    }

    @Test
    fun `a thread and a contact for the same person are one person`() {
        val people = SignalDirectory.merge(
            threads = listOf(row(alice, name = "Alice", number = "+15550001")),
            contacts = listOf(row(alice, name = "Alice Smith", number = "+15550001")),
            selfAci = self
        )

        assertEquals(1, people.size)
        // The thread's name wins: it came from this phone's own address book or from the
        // bridge, either of which is nearer to what the reader calls her.
        assertEquals("Alice", people[0].name)
    }

    @Test
    fun `the contacts sync names a thread that has no name of its own`() {
        val people = SignalDirectory.merge(
            threads = listOf(row(alice)),
            contacts = listOf(row(alice, name = "Alice", number = "+15550001")),
            selfAci = self
        )

        assertEquals("Alice", people[0].name)
        assertEquals("+15550001", people[0].number)
    }

    @Test
    fun `with no name anywhere the number stands in, and then the service id`() {
        val people = SignalDirectory.merge(
            threads = listOf(row(alice, number = "+15550001"), row(bruno)),
            contacts = listOf(),
            selfAci = self
        )

        val byKey = people.associateBy { person -> person.threadKey }
        assertEquals("+15550001", byKey.getValue("direct:$alice").name)
        assertEquals(
            bruno.take(SignalDirectory.SHORT_SERVICE_ID),
            byKey.getValue("direct:$bruno").name
        )
        // Nothing is ever nameless: a row in a list has to say something.
        assertTrue(people.none { person -> person.name.isBlank() })
    }

    @Test
    fun `note to self stays as a thread and never arrives again from the contacts sync`() {
        val people = SignalDirectory.merge(
            threads = listOf(row(self, name = "Note to Self")),
            contacts = listOf(row(self, name = "David", number = "+15550000")),
            selfAci = self
        )

        assertEquals(1, people.size)
        assertEquals("Note to Self", people[0].name)
    }

    @Test
    fun `the account itself is dropped when only the contacts sync knows it`() {
        val people = SignalDirectory.merge(
            threads = listOf(),
            contacts = listOf(row(self, name = "David"), row(alice, name = "Alice")),
            selfAci = self
        )

        assertEquals(listOf("Alice"), people.map { person -> person.name })
    }

    @Test
    fun `people read in the order they would be looked for`() {
        val people = SignalDirectory.merge(
            threads = listOf(row(bruno, name = "bruno")),
            contacts = listOf(row(alice, name = "Alice")),
            selfAci = self
        )

        assertEquals(listOf("Alice", "bruno"), people.map { person -> person.name })
    }

    @Test
    fun `a thread with no service id at all is not a person`() {
        val people = SignalDirectory.merge(
            threads = listOf(row("", name = "nobody")),
            contacts = listOf(),
            selfAci = self
        )

        assertTrue(people.isEmpty())
    }

    @Test
    fun `the readers own address book names somebody the account cannot`() {
        val people = SignalDirectory.merge(
            threads = listOf(),
            contacts = listOf(row(alice, number = "+15550001")),
            selfAci = self,
            nameForNumber = { number -> if (number == "+15550001") "Alice at home" else null }
        )

        assertEquals("Alice at home", people[0].name)
    }

    @Test
    fun `a name the account supplied beats the address book`() {
        val people = SignalDirectory.merge(
            threads = listOf(),
            contacts = listOf(row(alice, name = "Alice", number = "+15550001")),
            selfAci = self,
            nameForNumber = { "somebody else" }
        )

        assertEquals("Alice", people[0].name)
    }

    @Test
    fun `an address book that does not know the number changes nothing`() {
        val people = SignalDirectory.merge(
            threads = listOf(),
            contacts = listOf(row(alice, number = "+15550001")),
            selfAci = self,
            nameForNumber = { null }
        )

        assertEquals("+15550001", people[0].name)
    }

    @Test
    fun `a counterpart that is only a number is looked up as one`() {
        val people = SignalDirectory.merge(
            threads = listOf(row("+15550001")),
            contacts = listOf(),
            selfAci = self,
            nameForNumber = { number -> if (number == "+15550001") "Alice at home" else null }
        )

        assertEquals("Alice at home", people[0].name)
    }

    @Test
    fun `an account with no self yet still lists everybody`() {
        val people = SignalDirectory.merge(
            threads = listOf(),
            contacts = listOf(row(alice, name = "Alice")),
            selfAci = null
        )

        assertEquals(1, people.size)
    }
    @Test
    fun `people with names come before people without`() {
        // The Signal address book is browsed, not only searched, so what it opens on matters.
        // Sorting on the display name alone put every unnamed person first: what stands in for
        // their name is a phone number, and digits sort before letters.
        val people = SignalDirectory.merge(
            threads = emptyList(),
            contacts = listOf(
                SignalDirectory.Row("11111111-1111-4111-8111-111111111111", "", "+13162099737"),
                SignalDirectory.Row("22222222-2222-4222-8222-222222222222", "Ada Lovelace", ""),
                SignalDirectory.Row("33333333-3333-4333-8333-333333333333", "", "+14044082757"),
                SignalDirectory.Row("44444444-4444-4444-8444-444444444444", "Grace Hopper", "")
            ),
            selfAci = null
        )

        assertEquals(listOf("Ada Lovelace", "Grace Hopper"), people.take(2).map { it.name })
        // Still present, still in order, underneath.
        assertEquals(listOf("+13162099737", "+14044082757"), people.drop(2).map { it.name })
    }

    @Test
    fun `a name found only in the reader's own address book still counts as a name`() {
        // The lookup is what makes these people recognisable at all, so it has to decide the
        // order too -- otherwise somebody Signal does not name, but the phone does, would be
        // sorted in with the strangers.
        val people = SignalDirectory.merge(
            threads = emptyList(),
            contacts = listOf(
                SignalDirectory.Row("11111111-1111-4111-8111-111111111111", "", "+13162099737"),
                SignalDirectory.Row("22222222-2222-4222-8222-222222222222", "", "+12079076604")
            ),
            selfAci = null,
            nameForNumber = { number -> "Maggie King".takeIf { number == "+12079076604" } }
        )

        assertEquals("Maggie King", people.first().name)
    }

}
