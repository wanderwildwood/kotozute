package com.wanderwildwood.kotozute.feature.settings.about

/** One piece of other people's work that ships in the app (or is fetched by it), and its licence. */
data class LicenceComponent(
    val name: String,
    val author: String,
    val licence: String,
    val url: String,
    /** A short line shown on the component's own page, e.g. where a downloaded file comes from. */
    val note: String? = null,
    /**
     * Its own full licence text(s), from the build-time list (`licenceComponentsFromJson`). When
     * there are any they are shown instead of the app's bundled text for the licence's family.
     */
    val texts: List<LicenceText> = emptyList(),
)

/**
 * A licence's full text: bundled as a raw resource ([rawResId]), or carried as [content] (the
 * build-time list holds the texts itself; [rawResId] is 0 then). [id] is a family id from
 * [licenceFamilies] where the text belongs to one.
 */
data class LicenceText(val id: String, val title: String, val rawResId: Int, val content: String? = null)

/** A further group of components, e.g. files the app downloads and does not carry; they get a row each like the rest, [note] on their pages. */
data class ComponentGroup(val heading: String, val note: String?, val components: List<LicenceComponent>)

/** Everything the Licences page shows, all text already in the app's own strings. */
data class LicencesData(
    val appName: String,
    val sourceUrl: String,
    val licenceLine: String,
    val components: List<LicenceComponent>,
    val texts: List<LicenceText>,
    val extraGroups: List<ComponentGroup> = emptyList(),
)

/** The words on the screens, from the app's strings.xml. */
data class LicencesLabels(
    val title: String,
    val back: String,
    /** The words of the app page's link to its source code ("Source code"). */
    val sourceHeading: String,
    /** "%s needs no licence text here." filled with the licence name, for a licence with no bundled text. */
    val noText: (String) -> String,
    /** "No browser is installed. The address is %s." filled with the address. */
    val noBrowser: (String) -> String,
)

/** Family ids in the order their full texts are listed. */
val licenceFamilies: List<String> = listOf("gpl", "gpl2-cpe", "lgpl", "apache", "mpl", "mit", "bsd", "ofl", "unicode", "cc-by", "cc0", "pd")

/** The family a licence name belongs to ("GPL-3.0-or-later" is "gpl"), or null when it has no text to show. */
fun licenceFamily(name: String): String? {
    val n = name.lowercase()
    return when {
        "classpath" in n -> "gpl2-cpe"
        "lgpl" in n || "lesser general public" in n -> "lgpl"
        "gpl" in n || "general public license" in n || "general public licence" in n -> "gpl"
        "unicode" in n -> "unicode"
        "apache" in n -> "apache"
        "mpl" in n || "mozilla" in n -> "mpl"
        "mit" in n.split(Regex("[^a-z]+")) -> "mit"
        "bsd" in n -> "bsd"
        "ofl" in n || "open font" in n -> "ofl"
        "cc-by" in n || "cc by" in n -> "cc-by"
        "cc0" in n -> "cc0"
        "public domain" in n || n.startsWith("pd") -> "pd"
        else -> null
    }
}

/** Every family a licence string names: "Apache-2.0 and BSD" is apache and bsd. */
fun licenceFamilies(licence: String): List<String> =
    licence.split(Regex("\\s+(?:and|or|\\+|/)\\s+|,|;")).mapNotNull { licenceFamily(it.trim()) }.distinct()

/** Key of the app's own row and page. */
const val APP_ROW_KEY = "app"

/** One row of the list: [key] opens a page, [title] is the first line, [subtitle] the second. */
data class LicenceRow(val key: String, val title: String, val subtitle: String)

/** What one licence page shows. [url] is the source link (app) or website (library); [texts] are full texts to read, empty when [licence] needs none. */
data class LicencePage(
    val title: String,
    val author: String?,
    val licence: String,
    val url: String?,
    val note: String?,
    val texts: List<LicenceText>,
)

/** Every component, extra groups included (their group note carried onto each), alphabetical by name. */
fun allComponents(data: LicencesData): List<LicenceComponent> =
    (data.components + data.extraGroups.flatMap { g -> g.components.map { it.copy(note = it.note ?: g.note) } })
        .sortedWith(compareBy<LicenceComponent> { it.name.lowercase() }.thenBy { it.name })

/**
 * The short licence name for the app's row: the SPDX-style name in [line] ("GPL-3.0-only"), or built from
 * "GNU General Public License, version 3 only / or later", or the line itself when it names neither.
 */
fun appLicenceName(line: String): String {
    Regex("""\b(?:A|L)?GPL-\d(?:\.\d)?-(?:only|or-later)\b""").find(line)?.let { return it.value }
    val version = Regex("""[Gg]eneral [Pp]ublic [Ll]icen[cs]e,? version (\d)(?:\.\d)? (only|or later)""").find(line)
    if (version != null) return "GPL-${version.groupValues[1]}.0-" + if (version.groupValues[2] == "only") "only" else "or-later"
    return line
}

/** The list: the app itself first, then one row per component, alphabetical. */
fun licenceRows(data: LicencesData): List<LicenceRow> =
    listOf(LicenceRow(APP_ROW_KEY, data.appName, appLicenceName(data.licenceLine))) +
        allComponents(data).map { LicenceRow("lib:${it.name}", it.name, it.author) }

/** The full texts to show for a licence string, in the order its families are named; none when it needs none. */
fun textsForLicence(licence: String, texts: List<LicenceText>): List<LicenceText> =
    licenceFamilies(licence).mapNotNull { id -> texts.firstOrNull { it.id == id } }

/** The page behind the row [key], or null when no row has it. */
fun licencePage(data: LicencesData, key: String): LicencePage? =
    if (key == APP_ROW_KEY) {
        LicencePage(data.appName, null, data.licenceLine, data.sourceUrl, null, textsForLicence(appLicenceName(data.licenceLine), data.texts))
    } else {
        allComponents(data).firstOrNull { "lib:${it.name}" == key }?.let {
            LicencePage(it.name, it.author, it.licence, it.url.ifEmpty { null }, it.note, it.texts.ifEmpty { textsForLicence(it.licence, data.texts) })
        }
    }

/** The texts in the house order (GPL first, then Apache, MPL, MIT, BSD, OFL, ...), one per id. */
fun orderedTexts(texts: List<LicenceText>): List<LicenceText> =
    texts.distinctBy { it.id }.sortedBy { t -> licenceFamilies.indexOf(t.id).let { if (it < 0) Int.MAX_VALUE else it } }

/** The components grouped by licence family, in the order of [orderedTexts]; "" holds the ones without a family. */
fun groupByLicence(components: List<LicenceComponent>): Map<String, List<LicenceComponent>> =
    components
        .flatMap { c -> licenceFamilies(c.licence).ifEmpty { listOf("") }.map { it to c } }
        .groupBy({ it.first }, { it.second })
        .toSortedMap(compareBy { licenceFamilies.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } })

/** Licence families whose page says in one line that no text is needed (attribution or none at all). */
val licenceFamiliesWithoutText: Set<String> = setOf("cc-by", "cc0", "pd")

/**
 * What is wrong with the data, as plain sentences; empty when it is fine. For the app's own test:
 * a component with no licence, a licence with a full text to show but none bundled or carried, a
 * licence the list cannot place in a family and has no text for, a bundled text nothing uses, a
 * repeated name, a link that is not https (a component may have no link at all).
 */
fun licencesProblems(data: LicencesData): List<String> {
    val problems = mutableListOf<String>()
    val all = data.components + data.extraGroups.flatMap { it.components }
    val needFamily = all.filter { it.texts.isEmpty() }
    val used = (needFamily.flatMap { licenceFamilies(it.licence) } + licenceFamilies(data.licenceLine)).toSet()
    val have = data.texts.map { it.id }.toSet()
    all.filter { it.licence.isBlank() }.forEach { problems += "${it.name}: no licence" }
    (used - have - licenceFamiliesWithoutText).forEach { problems += "no full text for $it" }
    needFamily.filter { it.licence.isNotBlank() && licenceFamilies(it.licence).isEmpty() }
        .forEach { problems += "${it.name}: no text for licence ${it.licence}" }
    (have - used).forEach { problems += "text $it is not used by anything" }
    if (data.texts.map { it.id }.size != have.size) problems += "a licence text is listed twice"
    if (all.map { it.name }.size != all.map { it.name }.toSet().size) problems += "a component is listed twice"
    all.filter { it.url.isNotEmpty() && !it.url.startsWith("https://") }.forEach { problems += "${it.name}: link is not https" }
    if (!data.sourceUrl.startsWith("https://")) problems += "source link is not https"
    return problems
}

/**
 * A licence text as the paragraphs a reader turns pages by: blocks split at blank lines, each
 * joined into one row. No word is dropped or repeated by it.
 */
fun licenceParagraphs(text: String): List<String> =
    text
        .replace("\r\n", "\n")
        .split(Regex("\n[ \t]*\n"))
        .map { block -> block.lines().joinToString(" ") { it.trim() }.trim() }
        .filter { it.isNotEmpty() }

/** How many rows MMD's list turns per page: `LazyColumnMMD`'s `scrollStep`, which `PagedColumn` leaves at its default. */
const val LICENCE_ROWS_PER_PAGE = 4

/**
 * How the Licences list is laid out so that a page is exactly [LICENCE_ROWS_PER_PAGE] whole rows.
 * MMD's list moves a fixed number of ROWS per swipe or chevron press, whatever their heights, so
 * with rows of differing heights a page ends mid-row (or leaves room for part of the next one), and
 * the last page, which the list shifts back to end at the final row, starts mid-row. If every row
 * is the same height and the list is exactly that many rows tall, every page is whole rows and the
 * last ends flush. [rowPx] is that height, [listPx] the list's height (leftover pixels are given up,
 * never shown as a sliver), [titleLines] how many lines the row's name may take (the rest is
 * ellipsised; the library's page shows it in full).
 */
data class LicenceListPlan(val rowPx: Int, val listPx: Int, val titleLines: Int)

/**
 * [availablePx] is the height the list has; [verticalPadPx] the padding above and below a row's
 * text, [titleLinePx] and [subtitleLinePx] the height of one line of each. The name gets as many
 * lines as fit beside the one subtitle line, from one to [maxTitleLines].
 */
fun licenceListPlan(
    availablePx: Int,
    verticalPadPx: Int,
    titleLinePx: Int,
    subtitleLinePx: Int,
    rowsPerPage: Int = LICENCE_ROWS_PER_PAGE,
    maxTitleLines: Int = 2,
): LicenceListPlan {
    val row = (availablePx / rowsPerPage).coerceAtLeast(1)
    val room = row - 2 * verticalPadPx - subtitleLinePx
    val lines = (room / titleLinePx.coerceAtLeast(1)).coerceIn(1, maxTitleLines)
    return LicenceListPlan(rowPx = row, listPx = row * rowsPerPage, titleLines = lines)
}

/**
 * The first row of each page when a list of [count] rows is turned [rowsPerPage] at a time, the way
 * MMD's list does: the start moves on by [rowsPerPage] and is held back so that the last page still
 * ends at the final row. With equal rows this is what the user sees; the tests use it to show that
 * every page holds [rowsPerPage] rows (or all of them, if there are fewer).
 */
fun licencePageStarts(count: Int, rowsPerPage: Int = LICENCE_ROWS_PER_PAGE): List<Int> {
    if (count <= 0) return emptyList()
    val last = (count - rowsPerPage).coerceAtLeast(0)
    return (0 until count step rowsPerPage).map { minOf(it, last) }.distinct()
}

/**
 * The first row of each page of a text page whose rows differ in height (title, author, link and
 * paragraphs): rows are taken in order while they fit the [viewportPx] with [gapPx] between them,
 * and the next page starts at the first row that did not. No row is cut at the foot and none is
 * skipped; a row taller than a whole page gets a page to itself (the one case that must cut).
 * [keepWithNext] says which rows (headings) must not end a page: such a row moves to the next one,
 * unless it would then leave its page empty. [heights] are measured in px.
 */
fun licencePageStartsByHeight(
    heights: List<Int>,
    gapPx: Int,
    viewportPx: Int,
    keepWithNext: List<Boolean> = emptyList(),
): List<Int> {
    val starts = mutableListOf<Int>()
    var s = 0
    while (s < heights.size) {
        starts += s
        var used = 0
        var e = s
        while (e < heights.size) {
            val add = heights[e] + if (e == s) 0 else gapPx
            if (e > s && used + add > viewportPx) break
            used += add
            e++
        }
        if (e < heights.size && e > s + 1 && keepWithNext.getOrNull(e - 1) == true) e--
        s = e
    }
    return starts
}
