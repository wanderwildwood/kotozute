package com.wanderwildwood.kotozute.feature.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegisterProgressTest {

    private class MemStore : RegisterProgress.Store {
        var data: Map<String, String> = emptyMap()
        override fun read() = data
        override fun write(values: Map<String, String>) { data = values }
    }

    private var now = 1_000L
    private val disk = MemStore()
    private fun fresh() = RegisterProgress(disk) { now }   // a rebuilt screen or a new process

    @Test
    fun `leaving for the text message and coming back resumes at the code`() {
        fresh().codeSent("session-1", "+1 555 010 0100", askedForACall = false)
        now += 60_000
        val back = fresh().resume()
        assertEquals(RegisterProgress.Saved("session-1", "+1 555 010 0100", false), back)
    }

    @Test
    fun `the way the code was asked for is kept`() {
        fresh().codeSent("s", "+1 555 010 0101", askedForACall = true)
        assertTrue(fresh().resume()!!.askedForACall)
    }

    @Test
    fun `an old attempt is dropped and the store emptied`() {
        fresh().codeSent("s", "+1 555 010 0100", false)
        now += RegisterProgress.WINDOW_MS + 1
        assertNull(fresh().resume())
        assertTrue(disk.data.isEmpty())
    }

    @Test
    fun `forgetting leaves nothing to resume`() {
        val p = fresh()
        p.codeSent("s", "+1 555 010 0100", false)
        p.forget()
        assertNull(fresh().resume())
        assertTrue(disk.data.isEmpty())
    }

    @Test
    fun `a clock set backwards does not resume`() {
        fresh().codeSent("s", "+1 555 010 0100", false)
        now -= 5_000
        assertNull(fresh().resume())
    }

    @Test
    fun `garbage in the store is ignored`() {
        disk.data = mapOf("session" to "x", "at" to "not a number")
        assertNull(fresh().resume())
        assertTrue(disk.data.isEmpty())
    }
}
