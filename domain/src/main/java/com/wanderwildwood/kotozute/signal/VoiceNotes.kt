package com.wanderwildwood.kotozute.signal

/**
 * Marking an attachment as a voice note, from the composer all the way to the wire.
 *
 * Signal draws a distinction this app did not: an attachment carries a `voiceNote` flag, and
 * a client that sees it offers a play button where it would otherwise offer a file. Without
 * the flag a recording arrives as `audio/aac`, which every Signal client shows as an
 * attachment to download rather than as something somebody said.
 *
 * ## Why a data-URI parameter rather than a boolean
 *
 * The send path carries an attachment as **one string** -- an RFC 2397 data URI -- and that
 * one string is what flows through the repository, the scheduled-message store and the
 * Desktop Sync relay without any of them knowing what is in it. A parallel boolean would have
 * to be threaded through every one of those, and the first place that forgot to carry it
 * would send a voice note that arrives as a file, silently and only for that route.
 *
 * A media type may take parameters -- that is ordinary RFC 2045, and `data:` inherits it --
 * so the flag travels *inside* the value it describes and cannot be separated from it. A
 * reader that does not know the parameter sees `audio/aac` and is still correct; that is the
 * property worth having.
 *
 * ⚠ The parameter goes **before** `;base64`, which is where a media type's parameters belong.
 * After it, it would be read as part of the encoding token.
 */
object VoiceNotes {

    /** The media-type parameter that marks a recording as something somebody said. */
    const val PARAMETER = "voice-note"

    /** What a Signal voice note is recorded as. Signal's own choice; see its `AudioSlide`. */
    const val CONTENT_TYPE = "audio/aac"

    /**
     * Adds the marker to a data URI's media type.
     *
     * Returns [dataUri] unchanged when it is not a data URI or already carries the marker, so
     * this is safe to apply more than once.
     */
    fun mark(dataUri: String): String {
        if (!dataUri.startsWith(PREFIX)) return dataUri
        if (isMarked(dataUri)) return dataUri
        val comma = dataUri.indexOf(',')
        if (comma < 0) return dataUri

        val header = dataUri.substring(PREFIX.length, comma)
        val rest = dataUri.substring(comma)
        // Before the encoding token, and before the data. A header of "audio/aac;base64"
        // becomes "audio/aac;voice-note;base64".
        val marked = if (header.contains(BASE64_TOKEN)) {
            header.replace(BASE64_TOKEN, ";$PARAMETER$BASE64_TOKEN")
        } else {
            "$header;$PARAMETER"
        }
        return "$PREFIX$marked$rest"
    }

    /** Whether this attachment was recorded as a voice note. */
    fun isMarked(dataUri: String): Boolean {
        if (!dataUri.startsWith(PREFIX)) return false
        val comma = dataUri.indexOf(',')
        if (comma < 0) return false
        return dataUri.substring(PREFIX.length, comma)
            .split(';')
            .any { it.trim() == PARAMETER }
    }

    /**
     * Whether a received attachment of this content type can be played here.
     *
     * ⚠ Deliberately wider than [CONTENT_TYPE]. A voice note from another client is not
     * guaranteed to be AAC -- Signal Desktop and iOS have each written other things over the
     * years -- and refusing to offer playback because the type is unfamiliar turns a message
     * into an unopenable file. Anything audio is offered; the player says so if it cannot.
     */
    fun isPlayableAudio(contentType: String?): Boolean =
        contentType.orEmpty().startsWith("audio/")

    private const val PREFIX = "data:"
    private const val BASE64_TOKEN = ";base64"
}
