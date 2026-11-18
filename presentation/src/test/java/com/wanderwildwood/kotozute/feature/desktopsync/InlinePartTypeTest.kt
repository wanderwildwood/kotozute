package com.wanderwildwood.kotozute.feature.desktopsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which MMS parts the browser is handed to draw or play. The sender writes the type, so a
 * part that could hold script must only ever be a download.
 */
class InlinePartTypeTest {

    @Test
    fun `pictures sound and video are drawn or played`() {
        assertEquals("image/jpeg", inlinePartType("image/jpeg"))
        assertEquals("image/png", inlinePartType("IMAGE/PNG"))
        assertEquals("audio/amr", inlinePartType("audio/amr; name=voice.amr"))
        assertEquals("video/3gpp", inlinePartType("video/3gpp"))
    }

    @Test
    fun `a page or an svg is a download, never drawn`() {
        assertNull(inlinePartType("text/html"))
        assertNull(inlinePartType("image/svg+xml"))
        assertNull(inlinePartType("application/xhtml+xml"))
        assertNull(inlinePartType("text/xml"))
        assertNull(inlinePartType("application/javascript"))
        assertNull(inlinePartType(""))
    }

    @Test
    fun `every attachment opened on its own is sandboxed`() {
        assertTrue(ATTACHMENT_CSP.startsWith("sandbox"))
        assertTrue(ATTACHMENT_CSP.contains("default-src 'none'"))
    }
}
