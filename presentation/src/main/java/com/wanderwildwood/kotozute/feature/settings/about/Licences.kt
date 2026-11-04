package com.wanderwildwood.kotozute.feature.settings.about

import android.net.Uri
import com.wanderwildwood.kotozute.R

/**
 * Everything third-party that the APK holds. The list is made by the build, not by hand: the
 * AboutLibraries Gradle plugin walks the release runtime dependencies and writes
 * res/raw/aboutlibraries.json, one entry per artifact (each AndroidX artifact on its own), and
 * merges in config/libraries for what Gradle cannot see (the Signal service layer copied into
 * this repository, the forks this app continues, android-smsmms, the Lato font, desugaring).
 * Libraries under MIT or BSD carry their own license file, with its copyright line, from
 * config/licenses, so no generic MIT or BSD text is bundled. [LicencesJson.kt] (shared-licences) reads the file; nothing is fetched.
 */
object Licences {

    const val SOURCE_URL = "https://github.com/wanderwildwood/kotozute"

    /** The app's own licence, as its row names it. */
    const val APP_LICENCE = "AGPL-3.0-or-later"

    /**
     * One row of the Licenses list, and the page behind it. [texts] are the component's own full
     * texts from the generated list; where it has none, [raws] are the bundled family texts.
     */
    class Row(
        val title: String,
        val author: String,
        val licence: String,
        val url: String,
        val note: String?,
        val texts: List<LicenceText>,
        val raws: List<Int>,
        val isApp: Boolean = false,
    )

    /** The bundled full texts, by family, for a licence with no text of its own. */
    val RAW_TEXTS: List<Pair<String, Int>> = listOf(
        "agpl" to R.raw.licence_agpl3,
        "gpl" to R.raw.licence_gpl3,
        "gpl2-cpe" to R.raw.licence_gpl2_classpath,
        "apache" to R.raw.licence_apache2,
        "ofl" to R.raw.licence_ofl,
    )

    /**
     * The families a licence string names, AGPL told apart from GPL (the shared [licenceFamily]
     * puts both under "gpl", and this app carries the two texts).
     */
    fun families(licence: String): List<String> =
        licence.split(Regex("\\s+(?:and|or|\\+|/)\\s+|,|;")).mapNotNull { part ->
            val p = part.trim().lowercase()
            if ("agpl" in p || "affero" in p) "agpl" else licenceFamily(p)
        }.distinct()

    /** The bundled texts for a licence string, in the order its families are named. */
    fun rawsFor(licence: String): List<Int> =
        families(licence).mapNotNull { f -> RAW_TEXTS.firstOrNull { it.first == f }?.second }

    /** The list: the app itself first, then one row per component, alphabetical. */
    fun rows(appName: String, components: List<LicenceComponent>): List<Row> =
        listOf(Row(appName, "", APP_LICENCE, SOURCE_URL, null, emptyList(), rawsFor(APP_LICENCE), isApp = true)) +
            components.map { c ->
                // Its own texts, then the bundled text of each family it names that they leave out
                // (emoji2-bundled: its own Unicode text, and the Apache and OFL texts).
                val own = c.texts.map { it.id }.toSet()
                val rest = families(c.licence).filter { it !in own }
                val raws = rest.mapNotNull { f -> RAW_TEXTS.firstOrNull { it.first == f }?.second }
                Row(c.name, c.author, c.licence, c.url, c.note, c.texts, raws)
            }.sortedWith(compareBy<Row> { it.title.lowercase() }.thenBy { it.title })

    /** Valid web address for the source link. */
    fun sourceUri(): Uri = Uri.parse(SOURCE_URL)

    /** Text split into paragraphs, so the paged reader turns on whole paragraphs. */
    fun paragraphs(text: String): List<String> =
        text.replace("\r\n", "\n").split(Regex("\n\\s*\n")).map { it.trim('\n') }.filter { it.isNotBlank() }
}
