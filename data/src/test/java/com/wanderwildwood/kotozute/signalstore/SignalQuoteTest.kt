package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a reply sends about the message it quotes -- `PushSendJob.getQuoteFor`'s shape. */
class SignalQuoteTest {

    private val aci = "9d0652a3-dcc3-4d11-975f-74d61598733f"

    @Test
    fun `a quote names the original by timestamp and author, with its text`() {
        val quote = SignalQuote(sentAt = 1727000000000L, author = aci, text = "hello").toQuote()!!
        assertEquals(1727000000000L, quote.id)
        assertEquals(aci, quote.author.toString())
        assertEquals("hello", quote.text)
        assertTrue(quote.attachments!!.isEmpty())
    }

    @Test
    fun `one attachment rides along by type and name, with no thumbnail`() {
        val quote = SignalQuote(1L, aci, "", "image/png", "a.png").toQuote()!!
        val attached = quote.attachments!!.single()
        assertEquals("image/png", attached.contentType)
        assertEquals("a.png", attached.fileName)
        assertNull(attached.thumbnail)
    }

    @Test
    fun `an unknown type falls back to jpeg and a blank name to none, as upstream`() {
        val attached = SignalQuote(1L, aci, "", "", "").toQuote()!!.attachments!!.single()
        assertEquals("image/jpeg", attached.contentType)
        assertNull(attached.fileName)
    }

    /** Upstream sends no quote rather than one pointing at nobody. */
    @Test
    fun `an author that is not a service id gives no quote`() {
        assertNull(SignalQuote(1L, "+15555550123", "hi").toQuote())
        assertNull(SignalQuote(1L, "", "hi").toQuote())
    }
}
