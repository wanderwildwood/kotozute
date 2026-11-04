package com.wanderwildwood.kotozute.feature.settings.about

/*
 * The libraries list made at BUILD time. The AboutLibraries Gradle plugin
 * (com.mikepenz.aboutlibraries.plugin.android) walks the variant's runtime dependency graph and
 * writes res/raw/aboutlibraries.json: one entry per artifact in the APK (each AndroidX artifact on
 * its own), its POM's licence, and the full licence texts. Entries for what Gradle cannot see
 * (fonts, data sets, native libraries, bundled binaries) come from the app's config/libraries and
 * config/licenses folders, which the plugin merges into the same file.
 *
 * Pure Kotlin, no Android and no JSON library: the app's tests read the generated file with it,
 * and Views-based apps use it as is. Nothing here touches the network.
 */

/**
 * The components listed in an aboutlibraries.json, each with its own full licence text(s) where
 * the file carries them ([LicenceText.content]); where it does not, the app's bundled family texts
 * are used (see [licencePage]). A name that occurs twice gets its artifact id added, so every row
 * key is unique.
 */
fun licenceComponentsFromJson(json: String): List<LicenceComponent> {
    val root = JsonReader(json).readValue() as? Map<*, *> ?: return emptyList()
    val licences = (root["licenses"] as? Map<*, *>).orEmpty()
    val libs = (root["libraries"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
    val raw = libs.map { lib ->
        val id = lib.str("uniqueId")
        val name = lib.str("name").ifBlank { id.substringAfter(':') }
        val org = (lib["organization"] as? Map<*, *>)?.str("name").orEmpty()
        val devs = (lib["developers"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.str("name")?.ifBlank { null } }
        val author = org.ifBlank { devs.distinct().joinToString(", ") }.ifBlank { id.substringBefore(':') }
        val keys = (lib["licenses"] as? List<*>).orEmpty().mapNotNull { it as? String }
        val entries = keys.map { k -> k to (licences[k] as? Map<*, *>) }
        val licence = entries.joinToString(", ") { (k, l) -> l?.str("spdxId")?.ifBlank { null } ?: l?.str("name")?.ifBlank { null } ?: k }
        val texts = entries.mapNotNull { (k, l) ->
            val content = l?.str("content").orEmpty()
            if (content.isBlank()) null else LicenceText(licenceFamily(l?.str("spdxId").orEmpty().ifBlank { l?.str("name").orEmpty() }) ?: k, l?.str("name")?.ifBlank { null } ?: k, 0, content)
        }
        val website = https(lib.str("website")).ifBlank { https((lib["scm"] as? Map<*, *>)?.str("url").orEmpty()) }
        val version = lib.str("artifactVersion")
        // A Gradle artifact's page names its coordinates; an entry from config/libraries its description.
        val note = when {
            version.isNotBlank() && ':' in id -> "$id $version"
            else -> lib.str("description").ifBlank { null }
        }
        Triple(id, LicenceComponent(name, author, licence, website, note, texts), name)
    }
    // A repeated name gets its artifact id, or its whole id where those repeat too.
    fun repeats(names: List<String>) = names.groupingBy { it.lowercase() }.eachCount().filterValues { it > 1 }.keys
    val bare = repeats(raw.map { it.third })
    val byArtifact = raw.map { (id, _, name) -> if (name.lowercase() in bare) "$name (${id.substringAfter(':')})" else name }
    val still = repeats(byArtifact)
    return raw.mapIndexed { k, (id, c, name) ->
        val n = byArtifact[k]
        c.copy(name = if (n.lowercase() in still && n != name) "$name ($id)" else n)
    }.distinctBy { it.name }
}

/** "http://x" as "https://x" (the list's links are all https); anything else unchanged, blank for none. */
private fun https(url: String): String = url.trim().let { if (it.startsWith("http://")) "https://" + it.removePrefix("http://") else it }

private fun Map<*, *>.str(key: String): String = (this[key] as? String).orEmpty()

/** A small strict JSON reader: objects become Map, arrays List, numbers Double, plus String, Boolean and null. */
private class JsonReader(private val s: String) {
    private var i = 0

    fun readValue(): Any? {
        skip()
        if (i >= s.length) error("end of input")
        return when (s[i]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> word("true", true)
            'f' -> word("false", false)
            'n' -> word("null", null)
            else -> readNumber()
        }
    }

    private fun readObject(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        i++; skip()
        if (peek() == '}') { i++; return m }
        while (true) {
            skip(); val k = readString(); skip(); expect(':')
            m[k] = readValue(); skip()
            when (s.getOrNull(i++)) { ',' -> continue; '}' -> return m; else -> error("expected , or } at ${i - 1}") }
        }
    }

    private fun readArray(): List<Any?> {
        val l = ArrayList<Any?>()
        i++; skip()
        if (peek() == ']') { i++; return l }
        while (true) {
            l += readValue(); skip()
            when (s.getOrNull(i++)) { ',' -> continue; ']' -> return l; else -> error("expected , or ] at ${i - 1}") }
        }
    }

    private fun readString(): String {
        expect('"')
        val b = StringBuilder()
        while (true) {
            val c = s.getOrNull(i++) ?: error("unterminated string")
            when (c) {
                '"' -> return b.toString()
                '\\' -> when (val e = s.getOrNull(i++)) {
                    '"', '\\', '/' -> b.append(e)
                    'b' -> b.append('\b'); 'f' -> b.append('\u000C'); 'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t')
                    'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> error("bad escape at ${i - 1}")
                }
                else -> b.append(c)
            }
        }
    }

    private fun readNumber(): Double {
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        return s.substring(start, i).toDoubleOrNull() ?: error("bad value at $start")
    }

    private fun word(w: String, v: Any?): Any? { if (!s.startsWith(w, i)) error("bad value at $i"); i += w.length; return v }
    private fun expect(c: Char) { if (s.getOrNull(i) != c) error("expected $c at $i"); i++ }
    private fun peek() = s.getOrNull(i)
    private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
}
