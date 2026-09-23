package com.wanderwildwood.kotozute.feature.signal

import com.wanderwildwood.kotozute.R
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * [Words] read in English, the way a phone set to English would read them, with no Android.
 *
 * The strings come out of `values/strings.xml` and go through the same unescaping aapt does --
 * backslash escapes, double quotes that protect spaces, runs of whitespace collapsed and the
 * ends trimmed outside them. That last part is why this exists rather than a plain XML read: a
 * resource that begins with a space reads correctly in a text editor and loses the space on a
 * phone, and only a reader that behaves like aapt notices.
 */
internal object English {

    private val file: File = listOf(
        File("src/main/res/values/strings.xml"),
        File("presentation/src/main/res/values/strings.xml")
    ).first { it.isFile }

    private val strings: Map<String, String> by lazy {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to aapt(node.textContent)
        }
    }

    private val names: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    /** The English for [words]. */
    fun of(words: Words): String = words.resolve { id, args ->
        val text = strings.getValue(names.getValue(id))
        if (args.isEmpty()) text else String.format(Locale.US, text, *args)
    }

    /** What aapt makes of a string's text, entities already decoded by the XML parser. */
    private fun aapt(raw: String): String {
        // Each character is kept with whether it is collapsible whitespace, so an escaped or
        // quoted space survives the collapse and the trim while a bare one does not.
        val out = mutableListOf<Pair<Char, Boolean>>()
        var quoted = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length -> {
                    val next = raw[i + 1]
                    if (next == 'u') {
                        out += raw.substring(i + 2, i + 6).toInt(16).toChar() to false
                        i += 6
                        continue
                    }
                    out += when (next) {
                        'n' -> '\n'
                        't' -> '\t'
                        else -> next
                    } to false
                    i += 2
                    continue
                }
                c == '"' -> quoted = !quoted
                !quoted && c.isWhitespace() -> {
                    if (out.lastOrNull()?.second != true) out += ' ' to true
                }
                else -> out += c to false
            }
            i++
        }
        while (out.firstOrNull()?.second == true) out.removeAt(0)
        while (out.lastOrNull()?.second == true) out.removeAt(out.size - 1)
        return out.joinToString("") { it.first.toString() }
    }
}
