package com.wanderwildwood.kotozute.feature.settings.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LicencesTest {

    @Test fun listIsWholeAndNamesTheSignalAndQksmsCode() {
        val names = Licences.parts.map { it.name }
        assertTrue(Licences.parts.size > 20)
        assertTrue(names.any { it.startsWith("libsignal") })
        assertTrue(names.any { it.startsWith("RingRTC") })
        assertTrue(names.any { it.startsWith("QKSMS") })
        assertTrue(names.any { it.startsWith("Lato") })
        assertEquals(names.size, names.toSet().size)
    }

    @Test fun everyEntryHasAWebAddress() {
        Licences.parts.forEach { assertTrue(it.name, it.url.startsWith("https://")) }
        assertTrue(Licences.SOURCE_URL.startsWith("https://github.com/"))
    }

    @Test fun paragraphsSplitOnBlankLines() {
        assertEquals(listOf("a\nb", "c"), Licences.paragraphs("a\nb\n\n \nc\n"))
    }
}
