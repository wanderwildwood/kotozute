package com.wanderwildwood.kotozute.feature.compose

import com.wanderwildwood.kotozute.util.Signature
import org.junit.Assert.assertEquals
import org.junit.Test

class SignatureCountTest {

    @Test
    fun `the signature goes on a line of its own`() {
        assertEquals("See you at six\nAda", Signature.append("See you at six", "Ada"))
    }

    @Test
    fun `no signature leaves the text alone`() {
        assertEquals("See you at six", Signature.append("See you at six", ""))
        assertEquals("See you at six", Signature.counted("See you at six", ""))
    }

    @Test
    fun `the count takes in the signature and its line break`() {
        val draft = "a".repeat(150)
        assertEquals(150 + 1 + 3, Signature.counted(draft, "Ada").length)
    }

    @Test
    fun `an empty box counts nothing`() {
        assertEquals("", Signature.counted("", "Ada Whitlock"))
    }
}
