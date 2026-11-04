package com.wanderwildwood.kotozute.feature.settings.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LicencesJsonTest {
    // The shape the AboutLibraries plugin writes (15.x), cut down: two AndroidX artifacts, Guava with
    // two licences, an entry from config/libraries with no version, a licence without a text.
    private val json = """
        {"libraries": [
          {"uniqueId": "androidx.activity:activity", "artifactVersion": "1.13.0", "name": "Activity",
           "website": "https://developer.android.com/jetpack/androidx/releases/activity#1.13.0",
           "developers": [{"name": "The Android Open Source Project"}],
           "organization": {"name": "The Android Open Source Project"}, "licenses": ["Apache-2.0"], "funding": []},
          {"uniqueId": "androidx.core:core", "artifactVersion": "1.15.0", "name": "Core",
           "developers": [{"name": "The Android Open Source Project"}], "licenses": ["Apache-2.0"]},
          {"uniqueId": "com.example:core", "artifactVersion": "2.0", "name": "Core",
           "scm": {"url": "http://example.com/core"}, "licenses": ["BSD-3-Clause"]},
          {"uniqueId": "com.google.guava:guava", "artifactVersion": "33.3.1-android", "name": "Guava: Google Core Libraries for Java",
           "website": "https://github.com/google/guava", "developers": [{"name": "Kevin Bourrillion"}, {"name": "Kevin Bourrillion"}],
           "organization": {"name": "Google LLC"}, "licenses": ["Apache-2.0", "abc123"]},
          {"uniqueId": "lato-font", "name": "Lato", "description": "The typeface, bundled with MMD.",
           "developers": [{"name": "Łukasz Dziedzic"}], "website": "https://www.latofonts.com/", "licenses": ["OFL-1.1"]}
        ],
        "licenses": {
          "Apache-2.0": {"name": "Apache License 2.0", "url": "https://spdx.org/licenses/Apache-2.0.html",
            "content": "Apache License\nVersion 2.0, January 2004\n\n   TERMS AND \"CONDITIONS\"", "spdxId": "Apache-2.0", "hash": "Apache-2.0"},
          "BSD-3-Clause": {"name": "BSD 3-Clause \"New\" or \"Revised\" License", "spdxId": "BSD-3-Clause", "hash": "BSD-3-Clause",
            "content": "Copyright (c) <year> <owner>.\n\nRedistribution and use"},
          "OFL-1.1": {"name": "SIL Open Font License 1.1", "spdxId": "OFL-1.1", "hash": "e625", "content": "SIL OPEN FONT LICENSE"},
          "abc123": {"name": "Some Licence", "url": "https://example.org/l", "hash": "abc123"}
        }}
    """.trimIndent()

    private val comps = licenceComponentsFromJson(json)
    private fun named(n: String) = comps.single { it.name == n }

    @Test fun everyArtifactIsItsOwnRow() {
        assertEquals(5, comps.size)
        assertEquals("Activity", named("Activity").name)
        assertEquals("The Android Open Source Project", named("Activity").author)
        assertEquals("androidx.activity:activity 1.13.0", named("Activity").note)
    }

    @Test fun repeatedNamesGetTheirArtifactId() {
        // both are "Core" and both artifacts are "core": the whole id tells them apart
        assertEquals(listOf("Core (androidx.core:core)", "Core (com.example:core)"), comps.filter { it.name.startsWith("Core") }.map { it.name })
        val two = licenceComponentsFromJson("""{"libraries":[{"uniqueId":"a:x","name":"N","licenses":[]},{"uniqueId":"a:y","name":"N","licenses":[]}],"licenses":{}}""")
        assertEquals(listOf("N (x)", "N (y)"), two.map { it.name })
        assertEquals(comps.size, comps.map { it.name }.toSet().size)
    }

    @Test fun licenceLineAndOwnTextsComeFromTheFile() {
        val g = named("Guava: Google Core Libraries for Java")
        assertEquals("Google LLC", g.author)
        assertEquals("Apache-2.0, Some Licence", g.licence)
        assertEquals(1, g.texts.size) // the second licence has no text in the file
        assertEquals("apache", g.texts[0].id)
        assertEquals("Apache License 2.0", g.texts[0].title)
        assertTrue(g.texts[0].content!!.endsWith("TERMS AND \"CONDITIONS\""))
        assertEquals("bsd", comps.first { it.licence == "BSD-3-Clause" }.texts.single().id)
    }

    @Test fun linksAreHttpsOrNoneAndConfigEntriesKeepTheirDescription() {
        assertEquals("https://example.com/core", comps.first { it.licence == "BSD-3-Clause" }.url)
        assertEquals("", comps.first { it.author == "The Android Open Source Project" && it.url.isEmpty() }.url)
        val lato = named("Lato")
        assertEquals("Łukasz Dziedzic", lato.author)
        assertEquals("The typeface, bundled with MMD.", lato.note)
    }

    @Test fun aDuplicateAuthorIsNamedOnce() {
        val d = licenceComponentsFromJson("""{"libraries":[{"uniqueId":"a:b","name":"B","developers":[{"name":"X"},{"name":"X"},{"name":"Y"}],"licenses":["MIT"]}],"licenses":{}}""")
        assertEquals("X, Y", d.single().author)
        assertEquals("MIT", d.single().licence)
        assertTrue(d.single().texts.isEmpty())
    }

    @Test fun badOrEmptyInputGivesNothingOrThrows() {
        assertTrue(licenceComponentsFromJson("[]").isEmpty())
        assertTrue(licenceComponentsFromJson("{}").isEmpty())
        assertTrue(runCatching { licenceComponentsFromJson("{\"libraries\": [") }.isFailure)
    }
}
