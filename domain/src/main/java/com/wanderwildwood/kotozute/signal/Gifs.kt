package com.wanderwildwood.kotozute.signal

/**
 * Marking an attachment as a GIF, from the picker to the wire -- the way [VoiceNotes] marks a
 * recording, and for the same reason: the flag rides inside the data URI, so no route the
 * attachment takes can drop it.
 *
 * A GIF from Signal's keyboard is not a `.gif` at all. Upstream's `GiphyMp4Repository` fetches
 * GIPHY's MP4 rendition and sends it as `video/mp4` with `AttachmentPointer.Flags.GIF`, and
 * every Signal client plays such a video silently, on a loop, without a play button. Without
 * the flag it arrives as an ordinary video.
 */
object Gifs {

    /** The media-type parameter that marks a video as a GIF. */
    const val PARAMETER = "gif"

    /** What a GIF is sent as. Upstream's `MediaUtil.VIDEO_MP4`; see [Gifs]. */
    const val CONTENT_TYPE = "video/mp4"

    fun mark(dataUri: String): String = VoiceNotes.markParameter(dataUri, PARAMETER)

    fun isMarked(dataUri: String): Boolean = VoiceNotes.hasParameter(dataUri, PARAMETER)
}
