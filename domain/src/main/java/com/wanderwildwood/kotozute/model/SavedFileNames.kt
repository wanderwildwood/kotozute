package com.wanderwildwood.kotozute.model

/**
 * What to call a picture, video or file that is being saved out of a message, and what kind
 * it is.
 *
 * Pure Kotlin on purpose, with its own short table of types: Android's `MimeTypeMap` is a stub
 * in a plain JVM test, and the answer here is what makes a saved picture show up in the
 * Gallery or not.
 *
 * ⚠ The name is rebuilt rather than trusted. It arrives from whoever sent the message, so it
 * is theirs to choose: a path separator in it must never reach a file path.
 */
object SavedFileNames {

    /** The few types worth knowing without asking the system; it is asked for the rest. */
    private val extensions = mapOf(
        "image/jpeg" to "jpg", "image/png" to "png", "image/gif" to "gif",
        "image/webp" to "webp", "image/heic" to "heic", "image/heif" to "heif",
        "image/bmp" to "bmp", "image/vnd.wap.wbmp" to "wbmp",
        "video/mp4" to "mp4", "video/3gpp" to "3gp", "video/3gpp2" to "3g2",
        "video/webm" to "webm", "video/quicktime" to "mov", "video/x-matroska" to "mkv",
        "audio/mpeg" to "mp3", "audio/mp4" to "m4a", "audio/aac" to "aac",
        "audio/ogg" to "ogg", "audio/amr" to "amr", "audio/3gpp" to "3gp",
        "text/x-vcard" to "vcf", "text/vcard" to "vcf", "text/plain" to "txt",
        "application/pdf" to "pdf", "application/epub+zip" to "epub"
    )

    /** Carriers and phones disagree about some names; these are the ones seen in the wild. */
    private val synonyms = mapOf(
        "image/jpg" to "image/jpeg", "image/pjpeg" to "image/jpeg",
        "video/mpeg4" to "video/mp4", "video/x-m4v" to "video/mp4"
    )

    /** The type alone: lower case, no `; charset=` tail, the usual misspellings folded. */
    fun normalizeType(type: String?): String {
        val bare = type.orEmpty().substringBefore(';').trim().lowercase()
        return synonyms[bare] ?: bare
    }

    /** Whether the type says nothing a file manager could use. */
    fun isVague(type: String?): Boolean {
        val bare = normalizeType(type)
        return !bare.contains('/') || bare == "application/octet-stream" || bare.endsWith("/*")
    }

    /** A picture or a video: the two kinds that go to the Gallery rather than a picker. */
    fun isGalleryMedia(type: String?): Boolean {
        val bare = normalizeType(type)
        return bare.startsWith("image/") && !isVague(bare) || bare.startsWith("video/") && !isVague(bare)
    }

    fun isVideo(type: String?): Boolean = normalizeType(type).startsWith("video/") && !isVague(type)

    /**
     * The extension for a type, without the dot, or null when nothing knows it.
     *
     * @param lookup asked when the short table does not know the type, e.g. `MimeTypeMap`.
     */
    fun extensionFor(type: String?, lookup: (String) -> String? = { null }): String? {
        val bare = normalizeType(type)
        if (isVague(bare)) return null
        return extensions[bare]
            ?: runCatching { lookup(bare) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * A name that is safe to write and that says what it is.
     *
     * - Anything up to the last separator is dropped, and anything outside letters, digits,
     *   dot, dash and underscore becomes an underscore (leading and trailing dots and
     *   underscores go, so there is no hidden file and no `..`).
     * - A name with no extension gets one from the type. A name with an extension keeps it:
     *   it is what a reader app matches on.
     * - No name at all becomes [fallbackStem] plus the extension, never a bare word.
     *
     * @param fallbackStem used when the sender gave no usable name; make it unique (an id).
     */
    fun nameFor(
        proposed: String?,
        type: String?,
        fallbackStem: String,
        lookup: (String) -> String? = { null }
    ): String {
        val base = proposed.orEmpty().substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = base.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_', '-').take(120)
        val stem = cleaned.ifBlank { fallbackStem.replace(Regex("[^A-Za-z0-9._-]"), "_") }
        val tail = stem.substringAfterLast('.', "")
        val hasExtension = '.' in stem && tail.length in 1..5
        if (hasExtension) return stem
        val extension = extensionFor(type, lookup) ?: return stem
        return "$stem.$extension"
    }
}
