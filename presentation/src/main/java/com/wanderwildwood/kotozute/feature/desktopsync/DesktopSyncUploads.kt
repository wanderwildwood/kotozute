/*
 * Taking a file the browser uploaded and turning it into something the MMS send path can use.
 *
 * Nothing here needs a FileProvider or a new permission: Attachment accepts a file:// Uri, every
 * Uri extension it uses handles the file scheme, and MessageRepositoryImpl reads the bytes in
 * process and packs them into an MMSPart. No Uri is ever handed to another app.
 */
package com.wanderwildwood.kotozute.feature.desktopsync

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import com.wanderwildwood.kotozute.model.Attachment
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/** Where uploads are staged. Inside the app's own cache, which is what makes file:// safe here. */
private const val OUTGOING_DIR = "desktopsync-outgoing"

/** Anything left behind by a send that failed is swept after this long. */
private val STALE_AFTER_MS = TimeUnit.HOURS.toMillis(1)

/**
 * Copy an uploaded temp file into the app's cache and wrap it as an [Attachment].
 *
 * The copy is not optional. NanoHTTPD hands multipart parts over as temp files that its
 * TempFileManager deletes the moment the request ends, while the send is asynchronous — so an
 * Attachment pointing at the temp file would be reading a deleted path by the time the MMS is
 * built. Owning the copy is also what lets the interactor clean up after itself: it calls
 * removeCacheFile() on every file:// attachment once the bytes are in the MMS database.
 */
fun stageUpload(context: Context, source: File, uploadedName: String?): Attachment? {
    if (!source.exists() || source.length() == 0L)
        return null

    val directory = File(context.cacheDir, OUTGOING_DIR).apply { mkdirs() }
    sweepStale(directory)

    // The extension is load-bearing, not cosmetic: for a file:// Uri, Attachment.getType() asks
    // MimeTypeMap about the extension and nothing else. Get it wrong and isImage() is false, the
    // image skips scaling entirely, and a photo goes out at full size against a carrier limit
    // measured in hundreds of kilobytes.
    val extension = sniffExtension(source)
        ?: uploadedName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && it.length <= 5 }
        ?: "bin"

    val staged = File(directory, "upload-${System.currentTimeMillis()}-${source.name.hashCode()}.$extension")

    val attachment = runCatching {
        source.copyTo(staged, overwrite = true)
        Attachment(context, Uri.fromFile(staged))
    }.onFailure { error ->
        Timber.w(error, "Desktop Sync: could not stage an upload")
        staged.delete()
    }.getOrNull() ?: return null

    // Refuse a picture the phone cannot actually read. The send path decodes and rescales every
    // image, and a decode failure there throws out of the interactor and takes the WHOLE message
    // with it -- caption and all -- while the browser has already been told the send succeeded
    // and has cleared the composer. Better to find out here, where it can still be said out loud.
    if (attachment.isImage(context) && !decodes(staged)) {
        Timber.w("Desktop Sync: rejected an undecodable image (%s)", uploadedName ?: staged.name)
        staged.delete()
        return null
    }

    return attachment
}

/** Whether the image decoder can make sense of this file, asked without decoding the pixels. */
private fun decodes(file: File): Boolean {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }
    return options.outWidth > 0 && options.outHeight > 0
}

/**
 * Work out what a file actually is from its first bytes rather than trusting the name it arrived
 * under. A browser will usually send a sensible filename, but "usually" decides here whether a
 * picture is recognised as one, and a file dragged in from a screenshot tool may have no
 * extension at all.
 */
private fun sniffExtension(file: File): String? {
    val header = ByteArray(12)
    val read = runCatching {
        file.inputStream().use { stream -> stream.read(header) }
    }.getOrDefault(0)
    if (read < 12)
        return null

    fun matches(offset: Int, vararg bytes: Int): Boolean =
        bytes.withIndex().all { (index, byte) -> header[offset + index] == byte.toByte() }

    return when {
        matches(0, 0xFF, 0xD8, 0xFF) -> "jpg"
        matches(0, 0x89, 0x50, 0x4E, 0x47) -> "png"
        matches(0, 0x47, 0x49, 0x46, 0x38) -> "gif"
        matches(0, 0x52, 0x49, 0x46, 0x46) && matches(8, 0x57, 0x45, 0x42, 0x50) -> "webp"
        matches(4, 0x66, 0x74, 0x79, 0x70) -> "mp4"
        else -> null
    }
}

/** The MIME type an extension maps to, for reporting a rejection back to the browser. */
fun mimeTypeFor(extension: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"

/**
 * A send that never completes leaves its staged copy behind — the interactor only deletes the
 * ones it actually sent. Sweeping on the way in keeps that from growing without bound, and
 * avoids needing any scheduled work to do it.
 */
private fun sweepStale(directory: File) {
    val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
    runCatching {
        directory.listFiles()
            ?.filter { file -> file.lastModified() < cutoff }
            ?.forEach { file -> file.delete() }
    }
}
