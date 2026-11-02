package com.wanderwildwood.kotozute.feature.settings.about

import android.net.Uri
import com.wanderwildwood.kotozute.R

/**
 * Everything third-party that the APK holds: code linked from Gradle, code copied into this
 * repository, and the font. Kept as plain data so a test can check the list is whole.
 */
object Licences {

    const val SOURCE_URL = "https://github.com/wanderwildwood/kotozute"

    /** The licences whose full text the screen carries, besides this app's own. */
    enum class Kind(val label: String) {
        AGPL("AGPL-3.0"), GPL("GPL-3.0"), APACHE("Apache-2.0"), MIT("MIT"), BSD("BSD"), OFL("SIL OFL 1.1"),
        CDDL("CDDL 1.1 + GPLv2 with classpath exception"), SQLCIPHER("BSD-style (Zetetic)")
    }

    class Part(val name: String, val kind: Kind, val url: String)

    val parts: List<Part> = listOf(
        // Signal: AGPL, which is why the whole app is AGPL.
        Part("libsignal", Kind.AGPL, "https://github.com/signalapp/libsignal"),
        Part("RingRTC", Kind.AGPL, "https://github.com/signalapp/ringrtc"),
        Part("Signal Android service layer (copied in)", Kind.AGPL, "https://github.com/signalapp/Signal-Android"),
        // The SMS app this continues, and the forks between.
        Part("QKSMS, by Moez Bhatti", Kind.GPL, "https://github.com/moezbhatti/qksms"),
        Part("QUIK, by octoshrimpy", Kind.GPL, "https://github.com/octoshrimpy/quik"),
        Part("InkMessage, by lamdanAmiti", Kind.GPL, "https://github.com/lamdanAmiti/InkMessage"),
        Part("android-smsmms, by Jacob Klinker", Kind.APACHE, "https://github.com/klinker41/android-smsmms"),
        Part("Lato font, by tyPoland Lukasz Dziedzic", Kind.OFL, "https://www.latofonts.com"),
        Part("AndroidX, Material Components, ExoPlayer", Kind.APACHE, "https://developer.android.com/jetpack/androidx"),
        Part("Kotlin and kotlinx.coroutines", Kind.APACHE, "https://kotlinlang.org"),
        Part("Dagger", Kind.APACHE, "https://github.com/google/dagger"),
        Part("RxJava, RxAndroid, RxKotlin, RxBinding, RxDogTag", Kind.APACHE, "https://github.com/ReactiveX/RxJava"),
        Part("AutoDispose", Kind.APACHE, "https://github.com/uber/AutoDispose"),
        Part("Conductor", Kind.APACHE, "https://github.com/bluelinelabs/Conductor"),
        Part("Realm and realm-android-adapters", Kind.APACHE, "https://github.com/realm/realm-java"),
        Part("Moshi and OkHttp", Kind.APACHE, "https://github.com/square/moshi"),
        Part("Timber", Kind.APACHE, "https://github.com/JakeWharton/timber"),
        Part("Glide", Kind.APACHE, "https://github.com/bumptech/glide"),
        Part("PhotoView", Kind.APACHE, "https://github.com/Baseflow/PhotoView"),
        Part("FlexboxLayout", Kind.APACHE, "https://github.com/google/flexbox-layout"),
        Part("ShortcutBadger", Kind.APACHE, "https://github.com/leolin310148/ShortcutBadger"),
        Part("ZXing and zxing-android-embedded", Kind.APACHE, "https://github.com/zxing/zxing"),
        Part("libphonenumber-android", Kind.APACHE, "https://github.com/MichaelRocks/libphonenumber-android"),
        Part("RxPreferences", Kind.APACHE, "https://github.com/f2prateek/rx-preferences"),
        Part("SQLCipher for Android", Kind.SQLCIPHER, "https://github.com/sqlcipher/sqlcipher-android"),
        Part("NanoHTTPD", Kind.BSD, "https://github.com/NanoHttpd/nanohttpd"),
        Part("ez-vcard", Kind.BSD, "https://github.com/mangstadt/ez-vcard"),
        Part("datashare (Call Control)", Kind.MIT, "https://github.com/callcontrol"),
        Part("jdk9-deps", Kind.MIT, "https://github.com/pengrad/jdk9-deps"),
        Part("javax.annotation-api", Kind.CDDL, "https://github.com/javaee/javax.annotation")
    )


    /** One row of the Licences list, and the page behind it. [raw] is the licence's bundled full text, if there is one. */
    class Row(
        val title: String,
        val author: String,
        val licence: String,
        val url: String,
        val raw: Int?,
        val isApp: Boolean = false,
    )

    /** The raw full text of a kind of licence, or null where none is bundled (CDDL, named on its page). */
    fun rawFor(kind: Kind): Int? = when (kind) {
        Kind.AGPL -> R.raw.licence_agpl3
        Kind.GPL -> R.raw.licence_gpl3
        Kind.APACHE -> R.raw.licence_apache2
        Kind.MIT -> R.raw.licence_mit
        Kind.BSD, Kind.SQLCIPHER -> R.raw.licence_bsd
        Kind.OFL -> R.raw.licence_ofl
        Kind.CDDL -> null
    }

    /** "QKSMS, by Moez Bhatti" is the library QKSMS by Moez Bhatti. */
    fun titleOf(part: Part): String = part.name.substringBefore(", by ")
    fun authorOf(part: Part): String = part.name.substringAfter(", by ", "")

    /** The list: the app itself first, then one row per library, alphabetical. */
    fun rows(appName: String): List<Row> =
        listOf(Row(appName, "", Kind.AGPL.label + "-or-later", SOURCE_URL, rawFor(Kind.AGPL), isApp = true)) +
            parts.map { Row(titleOf(it), authorOf(it), it.kind.label, it.url, rawFor(it.kind)) }
                .sortedBy { it.title.lowercase() }

    /** One line per component, as the screen draws it. */
    fun line(part: Part): String = "${part.name}\n${part.kind.label}  ${part.url}"

    /** Valid web address for the source link. */
    fun sourceUri(): Uri = Uri.parse(SOURCE_URL)

    /** Text split into paragraphs, so the paged reader turns on whole paragraphs. */
    fun paragraphs(text: String): List<String> =
        text.replace("\r\n", "\n").split(Regex("\n\\s*\n")).map { it.trim('\n') }.filter { it.isNotBlank() }
}
