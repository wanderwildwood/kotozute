package com.wanderwildwood.kotozute.feature.settings.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LicencesLogicTest {
    private fun c(name: String, licence: String) = LicenceComponent(name, "someone", licence, "https://example.org/$name")
    private fun t(id: String) = LicenceText(id, id, 0)

    @Test fun familiesFromLicenceNames() {
        assertEquals("gpl", licenceFamily("GPL-3.0-or-later"))
        assertEquals("gpl", licenceFamily("GPL-3.0-only"))
        assertEquals("apache", licenceFamily("Apache-2.0"))
        assertEquals("mpl", licenceFamily("MPL-2.0"))
        assertEquals("mit", licenceFamily("MIT"))
        assertEquals("bsd", licenceFamily("BSD-3-Clause"))
        assertEquals("ofl", licenceFamily("OFL-1.1"))
        assertEquals("cc-by", licenceFamily("CC BY 4.0"))
        assertEquals("cc0", licenceFamily("CC0-1.0"))
        assertEquals("pd", licenceFamily("Public domain (PD-old-70-expired)"))
        assertEquals("gpl2-cpe", licenceFamily("GPL-2.0 with Classpath Exception"))
        assertEquals("unicode", licenceFamily("Unicode-3.0"))
        assertEquals("lgpl", licenceFamily("LGPL-2.1"))
        assertEquals(null, licenceFamily("Proprietary"))
    }

    @Test fun combinedLicencesNameEveryFamily() {
        assertEquals(listOf("apache", "bsd"), licenceFamilies("Apache-2.0 and BSD"))
        assertEquals(listOf("mit"), licenceFamilies("MIT"))
    }

    @Test fun textsAreOrderedGplFirstAndOnePerId() {
        val ordered = orderedTexts(listOf(t("ofl"), t("mit"), t("gpl"), t("apache"), t("mit")))
        assertEquals(listOf("gpl", "apache", "mit", "ofl"), ordered.map { it.id })
    }

    @Test fun componentsGroupByLicence() {
        val g = groupByLicence(listOf(c("a", "MIT"), c("b", "Apache-2.0"), c("c", "Apache-2.0 and BSD"), c("d", "Proprietary")))
        assertEquals(listOf("apache", "mit", "bsd", ""), g.keys.toList())
        assertEquals(listOf("b", "c"), g["apache"]!!.map { it.name })
        assertEquals(listOf("c"), g["bsd"]!!.map { it.name })
        assertEquals(listOf("d"), g[""]!!.map { it.name })
    }

    @Test fun problemsAreFoundAndAGoodSetHasNone() {
        val good = LicencesData("App", "https://github.com/x/y", "GPL-3.0-only", listOf(c("a", "MIT"), c("b", "CC0-1.0")), listOf(t("gpl"), t("mit")))
        assertTrue(licencesProblems(good).isEmpty())
        val missing = good.copy(texts = listOf(t("gpl")))
        assertEquals(listOf("no full text for mit"), licencesProblems(missing))
        val extra = good.copy(texts = good.texts + t("ofl"))
        assertEquals(listOf("text ofl is not used by anything"), licencesProblems(extra))
        assertTrue(licencesProblems(good.copy(components = listOf(c("a", "MIT"), c("a", "MIT")))).any { "twice" in it })
    }

    @Test fun aComponentWithItsOwnTextNeedsNoBundledText() {
        val own = LicenceComponent("Guava", "Google", "Apache-2.0", "https://github.com/google/guava", texts = listOf(LicenceText("apache", "Apache License 2.0", 0, "Apache License\n\nVersion 2.0")))
        val d = LicencesData("App", "https://github.com/x/y", "GPL-3.0-only", listOf(own), listOf(t("gpl")))
        assertTrue(licencesProblems(d).isEmpty())
        assertEquals("Apache License\n\nVersion 2.0", licencePage(d, "lib:Guava")!!.texts.single().content)
        // and its family text, bundled anyway, is then unused
        assertEquals(listOf("text apache is not used by anything"), licencesProblems(d.copy(texts = listOf(t("gpl"), t("apache")))))
    }

    @Test fun noLicenceAnUnplacedLicenceAndAnEmptyLinkAreCaught() {
        val d = LicencesData("App", "https://github.com/x/y", "GPL-3.0-only", listOf(c("a", ""), c("b", "Bouncy Castle Licence")), listOf(t("gpl")))
        assertEquals(listOf("a: no licence", "b: no text for licence Bouncy Castle Licence"), licencesProblems(d))
        val noLink = LicenceComponent("Lato", "Łukasz Dziedzic", "OFL-1.1", "", texts = listOf(LicenceText("ofl", "OFL", 0, "x")))
        assertTrue(licencesProblems(d.copy(components = listOf(noLink))).isEmpty())
        assertEquals(null, licencePage(d.copy(components = listOf(noLink)), "lib:Lato")!!.url)
        assertEquals(listOf("x: link is not https"), licencesProblems(d.copy(components = listOf(LicenceComponent("x", "y", "CC0-1.0", "http://x.org")))))
    }

    private fun data() = LicencesData(
        "App", "https://github.com/x/y", "GPL-3.0-only, holder someone",
        listOf(c("Zed", "MIT"), c("apache lib", "Apache-2.0"), c("Both", "Apache-2.0 and BSD"), c("Font", "CC BY 4.0")),
        listOf(t("gpl"), t("apache"), t("mit"), t("bsd")),
        listOf(ComponentGroup("Downloads", "Fetched when asked.", listOf(c("Model", "MIT")))),
    )

    @Test fun appRowIsFirstThenLibrariesAlphabetical() {
        val rows = licenceRows(data())
        assertEquals(APP_ROW_KEY, rows.first().key)
        assertEquals("App", rows.first().title)
        assertEquals("GPL-3.0-only", rows.first().subtitle)
        assertEquals(listOf("apache lib", "Both", "Font", "Model", "Zed"), rows.drop(1).map { it.title })
        assertEquals("someone", rows[1].subtitle)
    }

    @Test fun extraGroupComponentsGetARowAndTheGroupNote() {
        val p = licencePage(data(), "lib:Model")!!
        assertEquals("Fetched when asked.", p.note)
        assertEquals(listOf("mit"), p.texts.map { it.id })
    }

    @Test fun libraryPageMapsToItsLicenceTexts() {
        val d = data()
        assertEquals(listOf("apache", "bsd"), licencePage(d, "lib:Both")!!.texts.map { it.id })
        val p = licencePage(d, "lib:Zed")!!
        assertEquals("Zed", p.title)
        assertEquals("someone", p.author)
        assertEquals("https://example.org/Zed", p.url)
        assertEquals(listOf("mit"), p.texts.map { it.id })
    }

    @Test fun licenceWithNoTextHasNoTextsToShow() {
        assertTrue(licencePage(data(), "lib:Font")!!.texts.isEmpty())
    }

    @Test fun appPageCarriesTheGplAndTheSourceLink() {
        val p = licencePage(data(), APP_ROW_KEY)!!
        assertEquals("App", p.title)
        assertEquals(null, p.author)
        assertEquals("https://github.com/x/y", p.url)
        assertEquals(listOf("gpl"), p.texts.map { it.id })
    }

    @Test fun appLicenceNameIsShortened() {
        assertEquals("GPL-3.0-only", appLicenceName("App is free software: GPL-3.0-only, copyright x. Built on others."))
        assertEquals("GPL-3.0-only", appLicenceName("App is free software under the GNU General Public License, version 3 only. Copyright x."))
        assertEquals("GPL-3.0-or-later", appLicenceName("under the GNU General Public License, version 3 or later, as the app is."))
        assertEquals("Proprietary", appLicenceName("Proprietary"))
        assertEquals("gpl", licenceFamily("GNU General Public License, version 3"))
        assertEquals("lgpl", licenceFamily("GNU Lesser General Public License"))
    }

    @Test fun unknownKeyHasNoPage() {
        assertEquals(null, licencePage(data(), "lib:Nope"))
    }

    @Test fun paragraphsKeepEveryWord() {
        assertEquals(listOf("One two three", "Four five"), licenceParagraphs("One two\n three\n\n\nFour\r\nfive"))
    }

    @Test fun listPlanMakesEveryPageFourWholeRows() {
        val plan = licenceListPlan(availablePx = 1003, verticalPadPx = 12, titleLinePx = 40, subtitleLinePx = 34)
        assertEquals(250, plan.rowPx)
        assertEquals(1000, plan.listPx)
        assertEquals(0, plan.listPx % plan.rowPx)
        assertEquals(2, plan.titleLines)
    }

    @Test fun listPlanShortensTheNameWhenTheRowIsTight() {
        // 4 rows of 80 px: 80 - 24 - 34 = 22 px left, less than one title line; still one line, never zero.
        assertEquals(1, licenceListPlan(320, 12, 40, 34).titleLines)
        // a very tall row never takes more than two lines
        assertEquals(2, licenceListPlan(4000, 12, 40, 34).titleLines)
    }

    @Test fun listPlanSurvivesNoRoom() {
        val plan = licenceListPlan(0, 12, 40, 34)
        assertEquals(1, plan.rowPx)
        assertEquals(4, plan.listPx)
    }

    @Test fun lastPageEndsOnTheFinalRowAndNoPageIsShort() {
        assertEquals(listOf(0, 4, 5), licencePageStarts(9))
        assertEquals(listOf(0, 4), licencePageStarts(8))
        assertEquals(listOf(0), licencePageStarts(3))
        assertEquals(emptyList<Int>(), licencePageStarts(0))
        for (n in 1..40) for (start in licencePageStarts(n)) assertTrue(start + 4 <= n || start == 0)
    }

    @Test
    fun heightPagesFitTheViewportAndSkipNothing() {
        val h = listOf(30, 120, 40, 200, 90, 90, 90, 10, 60)
        val starts = licencePageStartsByHeight(h, gapPx = 8, viewportPx = 300)
        assertEquals(0, starts.first())
        val ends = starts.drop(1) + h.size
        var covered = 0
        starts.zip(ends).forEach { (s, e) ->
            assertEquals("no row skipped", covered, s)
            val used = (s until e).sumOf { h[it] } + 8 * (e - s - 1)
            assertTrue("page $s..$e is $used px", used <= 300)
            covered = e
        }
        assertEquals(h.size, covered)
        assertEquals(listOf(0, 3, 5), starts)
    }

    @Test
    fun heightPagesExactValues() {
        // 100+8+100 = 208 fits 210; the third 100 does not.
        assertEquals(listOf(0, 2, 4), licencePageStartsByHeight(listOf(100, 100, 100, 100, 50), 8, 210))
    }

    @Test
    fun aRowTallerThanThePageHasItsOwnAndHeadingsStayWithTheirText() {
        assertEquals(listOf(0, 1, 2), licencePageStartsByHeight(listOf(50, 900, 50), 8, 300))
        // heading (row 1) would end the first page: it moves on with its paragraph
        assertEquals(listOf(0, 1), licencePageStartsByHeight(listOf(250, 40, 200), 8, 300, listOf(false, true, false)))
        assertEquals(listOf(0, 1), licencePageStartsByHeight(listOf(100, 40, 200), 8, 300, listOf(false, true, false)))
        assertEquals(emptyList<Int>(), licencePageStartsByHeight(emptyList(), 8, 300))
    }
}
