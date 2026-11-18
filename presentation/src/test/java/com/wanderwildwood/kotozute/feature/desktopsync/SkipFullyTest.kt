package com.wanderwildwood.kotozute.feature.desktopsync

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading past the start of a range in a streamed Signal attachment.
 *
 * ⚠ A decrypting stream's `skip` answers 0 for bytes it has not decrypted yet, which is not
 * the end of the stream; a range answered from such a stream must still land on its start.
 */
class SkipFullyTest {

    /** Like a decrypting stream: `skip` never moves, only `read` does. */
    private class NoSkip(source: InputStream) : FilterInputStream(source) {
        override fun skip(n: Long): Long = 0
    }

    private fun stream(size: Int) = ByteArrayInputStream(ByteArray(size) { it.toByte() })

    @Test
    fun `skips where skip works`() {
        val s = stream(100)
        assertTrue(skipFully(s, 40))
        assertEquals(40, s.read())
    }

    @Test
    fun `skips by reading where skip answers 0`() {
        val s = NoSkip(stream(100))
        assertTrue(skipFully(s, 40))
        assertEquals(40, s.read())
    }

    @Test
    fun `nothing to skip is done at once`() {
        val s = NoSkip(stream(3))
        assertTrue(skipFully(s, 0))
        assertEquals(0, s.read())
    }

    @Test
    fun `false when the stream ends first`() {
        assertFalse(skipFully(NoSkip(stream(10)), 11))
        assertFalse(skipFully(stream(10), 11))
    }
}
