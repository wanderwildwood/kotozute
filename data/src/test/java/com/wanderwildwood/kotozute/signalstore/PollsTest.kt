package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PollsTest {

    private val poll = Polls.create("Lunch?", multiple = false, listOf("Soup", "Salad", "Both"))!!
    private val multi = Polls.create("Which days?", multiple = true, listOf("Mon", "Tue", "Wed"))!!

    @Test
    fun `limits are upstream's`() {
        assertNull(Polls.create("", false, listOf("a", "b")))
        assertNull(Polls.create("q", false, listOf("only one")))
        assertNull(Polls.create("q", false, (1..11).map { "$it" }))
        assertNull(Polls.create("q", false, listOf("a", "")))
        assertNull(Polls.create("x".repeat(201), false, listOf("a", "b")))
        assertNotNull(Polls.create("q", false, listOf("a", "b")))
    }

    @Test
    fun `a later vote replaces an earlier one, and a late old one changes nothing`() {
        val first = Polls.vote(poll, "ada", 1, listOf(0))!!
        val second = Polls.vote(first, "ada", 2, listOf(2))!!
        assertEquals(listOf(0, 0, 1), second.tally())
        assertNull(Polls.vote(second, "ada", 1, listOf(1)))
        assertNull(Polls.vote(second, "ada", 2, listOf(1)))
    }

    @Test
    fun `one choice only unless several are allowed`() {
        assertNull(Polls.vote(poll, "ada", 1, listOf(0, 1)))
        val v = Polls.vote(multi, "ada", 1, listOf(0, 2))!!
        assertEquals(listOf(1, 0, 1), v.tally())
    }

    @Test
    fun `out of range and ended polls are refused`() {
        assertNull(Polls.vote(poll, "ada", 1, listOf(3)))
        assertNull(Polls.vote(Polls.end(poll), "ada", 1, listOf(0)))
    }

    @Test
    fun `an empty selection takes the vote back`() {
        val voted = Polls.vote(poll, "ada", 1, listOf(1))!!
        val withdrawn = Polls.vote(voted, "ada", 2, emptyList())!!
        assertEquals(listOf(0, 0, 0), withdrawn.tally())
    }

    @Test
    fun `round trip`() {
        val p = Polls.end(Polls.vote(Polls.vote(multi, "ada", 1, listOf(1))!!, "tom", 3, listOf(0, 2))!!)
        val back = Polls.decode(Polls.encode(p))!!
        assertEquals(p, back)
        assertTrue(back.ended)
        assertNull(Polls.decode("not json"))
    }
}
