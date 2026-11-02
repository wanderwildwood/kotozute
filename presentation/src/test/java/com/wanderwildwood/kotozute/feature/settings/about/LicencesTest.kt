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

    @Test fun theListIsTheAppFirstThenEveryLibraryAlphabetically() {
        val rows = Licences.rows("Messaging")
        assertEquals(Licences.parts.size + 1, rows.size)
        assertTrue(rows.first().isApp)
        assertEquals("Messaging", rows.first().title)
        val rest = rows.drop(1).map { it.title.lowercase() }
        assertEquals(rest.sorted(), rest)
        // "QKSMS, by Moez Bhatti" is the library QKSMS, by Moez Bhatti
        val qksms = rows.first { it.title == "QKSMS" }
        assertEquals("Moez Bhatti", qksms.author)
    }

    @Test fun everyRowButTheJavaxOneHasABundledFullText() {
        val rows = Licences.rows("Messaging")
        assertTrue(rows.filter { it.raw == null }.all { it.licence.startsWith("CDDL") })
    }
}
