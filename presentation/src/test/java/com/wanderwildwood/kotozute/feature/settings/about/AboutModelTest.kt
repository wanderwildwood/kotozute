package com.wanderwildwood.kotozute.feature.settings.about

import com.wanderwildwood.kotozute.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AboutModel.kt, checked the way shared-about's HouseAboutTest checks HouseAbout.kt, plus Messaging's own words. */
class AboutModelTest {
    private val data = AboutData(
        title = "Sample 0.1.0",
        sections = listOf(
            AboutSection(null, "Counts the days."),
            AboutSection("Privacy", "Nothing leaves the phone."),
            AboutSection(null, "No ads."),
            AboutSection("How to contact us", "Write to email@wanderthe.dev."),
            AboutSection("Terms", "GPL-3.0-only."),
        ),
        sourceUrl = "https://github.com/wanderwildwood/sample",
        links = listOf(AboutLinkData("example.org/work", "https://example.org/work")),
    )

    private val app = Licences.Row("Sample", "", "GPL-3.0-only", "https://github.com/wanderwildwood/sample", null, emptyList(), listOf(7))
        .let { Licences.Row(it.title, it.author, it.licence, it.url, it.note, it.texts, it.raws, isApp = true) }
    private val okio = Licences.Row("Okio", "Square", "Apache-2.0", "https://square.github.io/okio/", "com.squareup.okio:okio 3.9.0",
        listOf(LicenceText("apache", "Apache (full text)", 0, "Apache one.\n\nApache two.")), emptyList())
    private val bare = Licences.Row("Lato", "Łukasz Dziedzic", "OFL-1.1", "", null, emptyList(), emptyList())
    private val raws = mapOf(7 to "GPL one.\n\nGPL two.")

    private fun drawers(rows: List<AboutRow>) = rows.filterIsInstance<AboutRow.Drawer>().map { it.drawer }

    @Test fun rowsAreInStyleOrder() {
        val rows = aboutRows(data)
        assertEquals(AboutRow.Intro(0, "Counts the days."), rows[0])
        assertEquals(AboutRow.Link("github.com/wanderwildwood/sample", "https://github.com/wanderwildwood/sample"), rows[1])
        assertEquals(AboutRow.Llama, rows[2])
        assertEquals(listOf("Privacy", "How to contact us", "Terms"), drawers(rows.subList(3, 6)).map { it.heading })
        assertEquals(AboutRow.Licences, rows[6])
        assertEquals("Links", (rows[7] as AboutRow.Drawer).drawer.heading)
        assertEquals(8, rows.size)
    }

    @Test fun theShortDescriptionIsPlainTextNotADrawer() {
        val two = data.copy(sections = listOf(AboutSection(null, "Counts the days."), AboutSection(null, "Offline.")) + data.sections.drop(1))
        assertEquals(listOf("Counts the days.", "Offline."), aboutIntro(two))
        val d = aboutDrawers(two)
        assertTrue(d.flatMap { it.bodies }.none { it == "Counts the days." || it == "Offline." })
        val intro = aboutItems(aboutRows(two), d.map { it.key }.toSet(), "Licenses") { emptyList() }.take(2)
        assertEquals(listOf("intro.0", "intro.1"), intro.map { it.key })
        assertEquals(intro, aboutItems(aboutRows(two), emptySet(), "Licenses") { error("closed") }.take(2))
    }

    @Test fun headedWordsStayInTheirDrawers() {
        val d = aboutDrawers(data)
        assertEquals(listOf("Nothing leaves the phone.", "No ads."), d[0].bodies)
        assertEquals(data.sections.drop(1).map { it.body }, d.flatMap { it.bodies })
    }

    @Test fun theAddressParagraphIsTheContactRow() {
        val items = aboutItems(aboutRows(data), setOf("d1"), "Licenses") { emptyList() }
        val contact = items.filterIsInstance<AboutItem.Contact>().single()
        assertEquals("d1.contact", contact.key)
        assertEquals(ABOUT_CONTACT_EMAIL, contact.address)
        assertEquals("Write to email@wanderthe.dev.", contact.label)
        assertTrue(items.filterIsInstance<AboutItem.Text>().none { ABOUT_CONTACT_EMAIL in it.text })
    }

    @Test fun withNoAddressParagraphTheWriteToRowStays() {
        val none = data.copy(sections = listOf(AboutSection("How to contact us", "Use the forum.")), links = emptyList())
        val items = aboutItems(aboutRows(none), setOf("d0", "contact"), "Licenses") { emptyList() }
        assertEquals(listOf("d0", "d0.0", ABOUT_LICENCES_KEY, "contact", "contact.contact"), items.dropWhile { it.key != "d0" }.map { it.key })
        assertNull((items.last() as AboutItem.Contact).label)
    }

    @Test fun allClosedShowsTheTopPlainThenHeads() {
        val items = aboutItems(aboutRows(data), emptySet(), "Licenses") { error("closed") }
        assertEquals(8, items.size)
        assertEquals(AboutItem.Text("intro.0", "Counts the days."), items[0])
        assertTrue(items[1] is AboutItem.Link && items[2] == AboutItem.Llama)
        assertTrue(items.drop(3).all { it is AboutItem.Head && !it.open })
    }

    @Test fun anOpenDrawerShowsItsRowsOneByOne() {
        val items = aboutItems(aboutRows(data), setOf("d0", "d1", "d2", "links"), "Licenses") { emptyList() }
        val keys = items.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(listOf("d0.0", "d0.1"), keys.filter { it.startsWith("d0.") })
        assertTrue(items.any { it is AboutItem.Link && it.url == "https://example.org/work" })
    }

    @Test fun licensesOpenInline() {
        var read = 0
        val readRaw = { id: Int -> read++; raws.getValue(id) }
        val rows = listOf(app, bare, okio)
        val closed = aboutLicenceItems("Sample is GPL.", rows, emptySet(), readRaw) { "$it: see the address" }
        assertEquals(0, read)
        assertEquals(listOf("lic.line", "lic/app", "lic/lib:Lato", "lic/lib:Okio"), closed.map { it.key })
        assertEquals("GPL-3.0-only", (closed[1] as AboutItem.Head).subtitle)
        assertEquals("Square", (closed[3] as AboutItem.Head).subtitle)
        assertTrue(closed.drop(1).all { it is AboutItem.Head && it.depth == 1 && !it.open })

        val lib = aboutLicenceItems("Sample is GPL.", rows, setOf("lic/lib:Okio"), readRaw) { it }
        assertEquals(listOf("Apache-2.0", "com.squareup.okio:okio 3.9.0", "Apache one.", "Apache two."),
            lib.filterIsInstance<AboutItem.Text>().drop(1).map { it.text })
        assertTrue(lib.any { it is AboutItem.Link && it.label == "square.github.io/okio" })

        val own = aboutLicenceItems("Sample is GPL.", rows, setOf("lic/app"), readRaw) { it }
        assertEquals(1, read)
        assertEquals(listOf("GPL one.", "GPL two."), own.filterIsInstance<AboutItem.Text>().filter { it.key.startsWith("lic/app.t0.") }.map { it.text })

        val none = aboutLicenceItems("x", rows, setOf("lic/lib:Lato"), readRaw) { "$it: see the address" }
        assertTrue(none.any { it is AboutItem.Text && it.text == "OFL-1.1: see the address" })

        val items = aboutItems(aboutRows(data), setOf(ABOUT_LICENCES_KEY), "Licenses") { closed }
        assertEquals(closed, items.subList(7, 7 + closed.size))
    }

    @Test fun licenceKeysStayUniqueAndStable() =
        assertEquals(listOf("lic/app", "lic/lib:Okio", "lic/lib:Okio#2"), aboutLicenceKeys(listOf(app, okio, okio)))

    @Test fun mailUriCarriesTheSubject() =
        assertEquals("mailto:email@wanderthe.dev?subject=Messaging%201.35.19", AboutContact.mailUri(ABOUT_CONTACT_EMAIL, "Messaging 1.35.19"))

    @Test fun addressDropsSchemeAndSlash() {
        assertEquals("wanderthe.dev", aboutAddress(ABOUT_WEBSITE_URL))
        assertEquals("square.link/u/AGu8oT10", aboutAddress(ABOUT_DONATE_URL))
        assertEquals("example.org/a", aboutAddress("https://example.org/a/"))
    }

    @Test fun goodDataHasNoProblems() = assertEquals(emptyList<String>(), aboutProblems(data))

    // Messaging's own words, read from strings.xml as the app reads them.

    private val strings: Map<String, String> by lazy {
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(File("src/main/res/values/strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
    }
    private val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
    private val messaging by lazy { messagingAboutData("Messaging 1.35.19") { strings.getValue(names.getValue(it)) } }

    @Test fun messagingHasNoProblems() = assertEquals(emptyList<String>(), aboutProblems(messaging))

    @Test fun messagingDrawersInOrder() {
        val rows = aboutRows(messaging)
        assertEquals(listOf(strings["about_description"]), rows.filterIsInstance<AboutRow.Intro>().map { it.text })
        assertEquals(AboutRow.Link("github.com/wanderwildwood/kotozute", "https://github.com/wanderwildwood/kotozute"), rows[1])
        assertEquals(AboutRow.Llama, rows[2])
        assertEquals(listOf("Privacy", "Who we are", "How to contact us", "Your rights", "Terms"), drawers(rows).map { it.heading })
        assertEquals(AboutRow.Licences, rows.last())
        // The contact sentence is the row that writes; no Contact or Links drawer of its own.
        assertEquals(ABOUT_CONTACT_EMAIL, drawers(rows).single { it.heading == "How to contact us" }.contact)
    }

    @Test fun everyOldFactIsStillSaid() {
        val said = aboutDrawers(messaging).flatMap { it.bodies } + aboutIntro(messaging)
        listOf("about_privacy_where", "about_privacy_permissions", "about_privacy_close", "privacy_who", "privacy_contact",
            "privacy_rights", "about_terms", "about_fork").forEach { assertTrue(it, strings.getValue(it) in said) }
        assertTrue(strings.getValue("about_fork").let { "QKSMS" in it && "QUIK" in it && "InkMessage" in it })
        assertTrue("Signal" in strings.getValue("about_terms"))
    }

    @Test fun theDescriptionIsShort() {
        val d = strings.getValue("about_description")
        assertTrue(d.count { it == '.' } <= 2 && d.length < 200)
    }
}
