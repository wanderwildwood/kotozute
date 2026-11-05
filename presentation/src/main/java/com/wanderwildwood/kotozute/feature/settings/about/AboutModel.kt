package com.wanderwildwood.kotozute.feature.settings.about

/*
 * The About's rows, as plain data: what is shown, in what order, with which drawers open.
 *
 * This mirrors the pure logic of shared-about's HouseAbout.kt (aboutIntro, aboutDrawers,
 * aboutRows, aboutItems, aboutLicenceItems, aboutAddress, aboutProblems) for an app drawn in
 * Views: Messaging has no Compose and must not gain it, so it cannot take the shared file as it
 * is. Keep it in step: when HouseAbout.kt's logic changes, change this the same way (the agreed
 * exception to "never edit an app's copy"). AboutDialog draws these rows; AboutModelTest checks
 * them the way HouseAboutTest does.
 *
 * Differences from the shared file, all on the Licenses side: this app keeps its own Licences
 * list (Licences.rows, the app first and then every component), and a library's full text comes
 * from its own texts or the bundled family texts (Licences.Row.raws), as its Licenses page did.
 */

const val ABOUT_WEBSITE_URL = "https://wanderthe.dev"
const val ABOUT_DONATE_URL = "https://square.link/u/AGu8oT10"

/** The one contact address, the same in every app. */
const val ABOUT_CONTACT_EMAIL = AboutContact.EMAIL

/** The key of the Licenses drawer. */
const val ABOUT_LICENCES_KEY = "licences"

/**
 * One block of the app's own words: a heading (or none) and its text. A headed section is a
 * drawer; a section with no heading is the next paragraph of the drawer above it, and ones before
 * any heading are the short description, shown plainly above the source, the llama and the drawers.
 */
data class AboutSection(val heading: String?, val body: String)

/** A link row: [label] is what is shown, [url] must be https. */
data class AboutLinkData(val label: String, val url: String)

/** What the app says about itself; [title] is the name and version in one line. */
data class AboutData(
    val title: String,
    val sections: List<AboutSection>,
    val sourceUrl: String,
    val links: List<AboutLinkData> = emptyList(),
)

/** A drawer of the app's own words: its [heading], its paragraphs, and any links or contact row it holds. */
data class AboutDrawer(
    val key: String,
    val heading: String,
    val bodies: List<String>,
    val links: List<AboutLinkData> = emptyList(),
    val contact: String? = null,
)

/** The rows, top to bottom, all closed. */
sealed interface AboutRow {
    data class Intro(val index: Int, val text: String) : AboutRow
    data class Drawer(val drawer: AboutDrawer) : AboutRow
    object Licences : AboutRow
    data class Link(val label: String, val url: String) : AboutRow
    object Llama : AboutRow
}

/** One row of the list as drawn, drawers opened: [key] is unique and stable. */
sealed interface AboutItem {
    val key: String

    /** A drawer's own row; [depth] 1 is a drawer inside Licenses, [subtitle] its second line. */
    data class Head(override val key: String, val heading: String, val open: Boolean, val depth: Int = 0, val subtitle: String? = null) : AboutItem
    data class Text(override val key: String, val text: String, val depth: Int = 0, val bold: Boolean = false) : AboutItem
    /** The contact row; [label] is the drawer's own sentence that gives the address, when there is one. */
    data class Contact(override val key: String, val address: String, val depth: Int = 0, val label: String? = null) : AboutItem
    data class Link(override val key: String, val label: String, val url: String, val depth: Int = 0) : AboutItem
    object Llama : AboutItem { override val key = "llama" }
}

/** The address as shown to a person: no scheme, no trailing slash. */
fun aboutAddress(url: String): String = url.removePrefix("https://").trimEnd('/')

/**
 * The app's sections as drawers: a headed section opens one, the unheaded sections after it are
 * its further paragraphs; unheaded ones before any heading are the short description. The contact
 * row goes into the first drawer whose words give [ABOUT_CONTACT_EMAIL]; with none, it gets a
 * drawer of its own, [contact]. Extra links get [links].
 */
fun aboutDrawers(data: AboutData, contact: String = "Contact", links: String = "Links"): List<AboutDrawer> {
    val drawers = mutableListOf<AboutDrawer>()
    var heading: String? = null
    val bodies = mutableListOf<String>()
    fun shut() {
        heading?.let { drawers.add(AboutDrawer("d${drawers.size}", it, bodies.toList())) }
        heading = null
        bodies.clear()
    }
    data.sections.forEach { s ->
        if (s.heading != null) {
            shut()
            heading = s.heading
        } else if (heading == null) {
            return@forEach // the short description: aboutIntro, shown above the drawers
        }
        bodies.add(s.body)
    }
    shut()
    val holder = drawers.indexOfFirst { d -> d.bodies.any { ABOUT_CONTACT_EMAIL in it } }
    if (holder >= 0) drawers[holder] = drawers[holder].copy(contact = ABOUT_CONTACT_EMAIL)
    if (data.links.isNotEmpty()) drawers.add(AboutDrawer("links", links, emptyList(), links = data.links))
    if (holder < 0) drawers.add(AboutDrawer("contact", contact, emptyList(), contact = ABOUT_CONTACT_EMAIL))
    return drawers
}

/** The unheaded sections before any heading: the app's short description, shown plainly at the top. */
fun aboutIntro(data: AboutData): List<String> =
    data.sections.takeWhile { it.heading == null }.map { it.body }

/**
 * The short description, the source, the llama, then the app's drawers, Licenses, and the Links
 * and Contact drawers the dialog makes: always this order (4 Nov 2026).
 */
fun aboutRows(data: AboutData, contact: String = "Contact", links: String = "Links"): List<AboutRow> {
    val drawers = aboutDrawers(data, contact, links)
    val own = drawers.filter { it.key.startsWith("d") }
    return buildList {
        aboutIntro(data).forEachIndexed { i, t -> add(AboutRow.Intro(i, t)) }
        add(AboutRow.Link(aboutAddress(data.sourceUrl), data.sourceUrl))
        add(AboutRow.Llama)
        own.forEach { add(AboutRow.Drawer(it)) }
        add(AboutRow.Licences)
        (drawers - own.toSet()).forEach { add(AboutRow.Drawer(it)) }
    }
}

/**
 * The rows as the list draws them: each drawer's own row, and, for the ones in [open], a row per
 * paragraph, link and contact line. An open Licenses drawer takes [licenceItems] under it.
 */
fun aboutItems(rows: List<AboutRow>, open: Set<String>, licencesHeading: String, licenceItems: () -> List<AboutItem>): List<AboutItem> = buildList {
    rows.forEach { row ->
        when (row) {
            is AboutRow.Drawer -> {
                val d = row.drawer
                val isOpen = d.key in open
                add(AboutItem.Head(d.key, d.heading, isOpen))
                if (isOpen) {
                    // The sentence that gives the address IS the contact row, pressed to write: no
                    // second "Write to" line saying the same (4 Nov 2026).
                    var said = false
                    d.bodies.forEachIndexed { i, b ->
                        val c = d.contact
                        if (c != null && !said && c in b) {
                            said = true
                            add(AboutItem.Contact("${d.key}.contact", c, label = b))
                        } else {
                            add(AboutItem.Text("${d.key}.$i", b))
                        }
                    }
                    d.links.forEachIndexed { i, l -> add(AboutItem.Link("${d.key}.link$i", l.label, l.url)) }
                    d.contact?.let { if (!said) add(AboutItem.Contact("${d.key}.contact", it)) }
                }
            }
            AboutRow.Licences -> {
                val isOpen = ABOUT_LICENCES_KEY in open
                add(AboutItem.Head(ABOUT_LICENCES_KEY, licencesHeading, isOpen))
                if (isOpen) addAll(licenceItems())
            }
            is AboutRow.Intro -> add(AboutItem.Text("intro.${row.index}", row.text))
            is AboutRow.Link -> add(AboutItem.Link("source", row.label, row.url))
            AboutRow.Llama -> add(AboutItem.Llama)
        }
    }
}

/** The stable key of each Licenses row: the app is "lic/app", a library "lic/lib:<name>". */
fun aboutLicenceKeys(rows: List<Licences.Row>): List<String> {
    val seen = mutableMapOf<String, Int>()
    return rows.map { r ->
        val base = if (r.isApp) "lic/app" else "lic/lib:${r.title}"
        val n = (seen[base] ?: 0) + 1
        seen[base] = n
        if (n == 1) base else "$base#$n"
    }
}

/**
 * Inside the Licenses drawer: the app's own license line ([appLine]), then one small drawer per
 * row of [rows] (the app first, then each library, name and author). An open one shows its
 * license, its address, any note, and its full text(s) paragraph by paragraph: its own texts,
 * then the bundled family texts, read by [readRaw] only when opened.
 */
fun aboutLicenceItems(
    appLine: String,
    rows: List<Licences.Row>,
    open: Set<String>,
    readRaw: (Int) -> String,
    noText: (String) -> String,
): List<AboutItem> = buildList {
    add(AboutItem.Text("lic.line", appLine, depth = 1))
    rows.zip(aboutLicenceKeys(rows)).forEach { (r, key) ->
        val isOpen = key in open
        val subtitle = (if (r.isApp) r.licence else r.author).ifBlank { null }
        add(AboutItem.Head(key, r.title, isOpen, depth = 1, subtitle = subtitle))
        if (!isOpen) return@forEach
        add(AboutItem.Text("$key.licence", r.licence, depth = 1))
        if (r.url.isNotBlank()) add(AboutItem.Link("$key.url", aboutAddress(r.url), r.url, depth = 1))
        r.note?.let { add(AboutItem.Text("$key.note", it, depth = 1)) }
        val fulls = r.texts.mapNotNull { t -> t.content ?: t.rawResId.takeIf { it != 0 }?.let(readRaw) } +
            r.raws.map(readRaw)
        if (fulls.isEmpty()) add(AboutItem.Text("$key.none", noText(r.licence), depth = 1))
        fulls.forEachIndexed { ti, full ->
            Licences.paragraphs(full).forEachIndexed { pi, p -> add(AboutItem.Text("$key.t$ti.$pi", p, depth = 1)) }
        }
    }
}

/** Plain sentences for what is wrong with the app's data (used in the test); empty is fine. */
fun aboutProblems(data: AboutData): List<String> = buildList {
    if (data.title.isBlank()) add("The title is empty.")
    data.sections.forEachIndexed { i, s -> if (s.body.isBlank()) add("Section ${i + 1} has no text.") }
    (data.links.map { it.url } + data.sourceUrl).forEach { if (!it.startsWith("https://")) add("The link $it is not https.") }
    val shown = data.links.map { it.label } + aboutAddress(data.sourceUrl)
    shown.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { add("The link $it is listed twice.") }
}
