package com.wanderwildwood.kotozute.feature.signal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.wanderwildwood.kotozute.extensions.getType
import java.io.ByteArrayOutputStream

/**
 * Turning a file into what the bridge takes: an RFC 2397 data URI.
 *
 * signal-cli accepts these directly, so nothing decrypted is written to disk on the way
 * through. Shared by the thread screen and the Desktop Sync relay, because a picture sent
 * from the browser and the same picture sent from the phone should arrive the same size
 * and in the same format -- two copies of this would drift apart on the first change.
 */
object SignalAttachment {

    /**
     * A phone photo base64s to several megabytes, and holding that twice over -- bytes and
     * string -- is how a small device runs out of memory mid-send.
     */
    const val MAX_IMAGE_EDGE = 1600
    const val MAX_BYTES = 24 * 1024 * 1024

    /** Thrown when the file is readable but too large to send. */
    class TooLarge : IllegalStateException("attachment too large")

    /**
     * Read [uri] and encode it. Images are downscaled and re-encoded as JPEG first, except
     * GIFs, where re-encoding would throw away the animation.
     */
    fun dataUri(context: Context, uri: Uri): String {
        // Uri.getType, not ContentResolver.getType: the latter returns null for a file://
        // Uri, which is what the Desktop Sync relay stages an upload as. That made every
        // picture sent from the browser go out as application/octet-stream -- unscaled, and
        // shown by the recipient's Signal as a file rather than an image.
        val resolver = context.contentResolver
        val type = uri.getType(context)
        val bytes = if (type.startsWith("image/")) {
            downscale(resolver, uri) ?: readBytes(resolver, uri)
        } else {
            readBytes(resolver, uri)
        }
        if (bytes.size > MAX_BYTES) throw TooLarge()
        val encodedType = if (type.startsWith("image/") && type != "image/gif") {
            "image/jpeg"
        } else {
            type
        }
        return "data:$encodedType;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** A MediaStore uri's last path segment is a row id, so ask for the real name. */
    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull()

    private fun readBytes(resolver: android.content.ContentResolver, uri: Uri): ByteArray =
        resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("cannot read $uri")

    /** Decodes at a reduced sample size, then recompresses. Null if it is not an image. */
    private fun downscale(resolver: android.content.ContentResolver, uri: Uri): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > MAX_IMAGE_EDGE || bounds.outHeight / sample > MAX_IMAGE_EDGE) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            bmp.recycle()
            out.toByteArray()
        }
    }
}
