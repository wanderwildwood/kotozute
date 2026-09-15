package com.wanderwildwood.kotozute.feature.signal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.wanderwildwood.kotozute.extensions.getType
import java.io.ByteArrayOutputStream

/**
 * Turning a file into what the send path takes: an RFC 2397 data URI.
 *
 * Carried in memory rather than as a path, so nothing decrypted is written to disk on the
 * way through. Shared by the thread screen and the Desktop Sync relay, because a picture sent
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

    /**
     * The widest a thumbnail is ever drawn, for when the view has not been measured yet.
     *
     * The panel is 480 pixels across, so nothing in a list is wider than that; decoding a
     * picture at more is buying pixels the screen cannot show. Upstream falls back to the
     * view's layout width and skips the constraint when even that is unknown
     * (`ThumbnailView.applySizing`); a fixed number is honest here because there is one
     * screen and it is this one.
     */
    const val THUMBNAIL_EDGE = 480

    /**
     * How much to divide a picture by so neither side is longer than [maxEdge].
     *
     * Its own function because two screens and the send path all need the same answer, and
     * because a decode with the wrong one is not a visible fault -- it is memory, spent
     * silently, until a phone this small runs out of it.
     *
     * Powers of two only: `BitmapFactory` rounds `inSampleSize` down to one anyway, so any
     * other value is a number that does not mean what it says.
     */
    fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
        var sample = 1
        while (width / sample > maxEdge || height / sample > maxEdge) {
            sample *= 2
        }
        return sample
    }

    /**
     * Decodes [bytes] no larger than it will be drawn.
     *
     * ⚠ **This used to decode at full resolution.** A picture taken on a modern phone is four
     * thousand pixels across, and a bitmap costs four bytes a pixel whatever the file size --
     * so a two-megabyte photo became a fifty-megabyte bitmap to fill a thumbnail a few hundred
     * pixels wide, and several of those at once is the whole heap on a phone like this.
     *
     * Upstream never decodes one unbounded: every conversation thumbnail goes through
     * `ThumbnailView.applySizing`, which puts Glide's `.override(width, height)` on the
     * request and lets `Downsampler` pick the sample size. This rail does not go through
     * Glide, so the same rule is applied by hand.
     */
    fun decodeBounded(bytes: ByteArray, maxEdge: Int = THUMBNAIL_EDGE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /**
     * What a decoded picture costs to keep, so a cache can be bounded by memory rather than
     * by how many things are in it.
     *
     * An LRU counted in entries has no bound worth the name: eight thumbnails is eight
     * megabytes or four hundred, depending entirely on what somebody sent.
     */
    fun bitmapBytes(bitmap: Bitmap): Int = bitmap.byteCount

    /** Thrown when the file is readable but too large to send. */
    class TooLarge : IllegalStateException("attachment too large")

    /**
     * Whether a file of [sizeBytes] is too large to send, given before anything is read.
     *
     * A size of zero or less means the question could not be answered -- a provider that
     * reports no size, which is common enough for a `file://` Uri. That is **not** a refusal:
     * refusing on an unknown size would block sends that are perfectly fine, and the check
     * after the read still catches a file that really is too big. It only means this device
     * has to find out the expensive way.
     */
    fun tooLargeToSend(sizeBytes: Long): Boolean = sizeBytes > MAX_BYTES

    /**
     * How large a file is, without reading it.
     *
     * ⚠ **The size check used to happen after the whole file was in memory.** `readBytes`
     * pulls the entire thing into a `ByteArray` and only then is its length compared against
     * [MAX_BYTES], so a video far over the limit is not refused -- it is loaded, and on a phone
     * with a small heap the app dies before reaching the line that would have refused it. A
     * guard placed after the thing it guards against is not a guard.
     *
     * Modelled on `ShareRepository.getSize`: ask the provider through `OpenableColumns.SIZE`,
     * and when it will not say, fall back to counting the stream through a small buffer
     * (`MediaUtil.getMediaSize`) -- which walks the file but never holds it.
     *
     * The `file://` arm is ours, not upstream's: the Desktop Sync relay stages an upload as a
     * `file://` Uri, `query` answers null for those, and a single `length()` is both cheaper
     * and more certain than a counting pass over a file that is about to be read again.
     */
    fun sizeOf(context: Context, uri: Uri): Long {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val path = uri.path ?: return 0L
            return java.io.File(path).length()
        }
        val declared = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (i >= 0 && c.moveToFirst() && !c.isNull(i)) c.getLong(i) else 0L
            } ?: 0L
        }.getOrDefault(0L)
        if (declared > 0) return declared

        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(4096)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                    // No reason to keep counting once the answer cannot change. Upstream
                    // counts the whole file because it wants the size for a record; here the
                    // only question is whether it is over the limit.
                    if (total > MAX_BYTES) return@use total
                }
                total
            } ?: 0L
        }.getOrDefault(0L)
    }

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
            // The downscale reads at a reduced sample size and hands back a bounded JPEG, so
            // a very large photo is made sendable rather than refused. Its fallback is a
            // whole-file read, which is not, so that arm is measured first like any other.
            downscale(resolver, uri) ?: readBounded(context, resolver, uri)
        } else {
            readBounded(context, resolver, uri)
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

    /**
     * Refuses before reading, then reads.
     *
     * The check after the read stays where it is -- a provider can report a size it does not
     * honour, and the second check is what catches that -- but by then the memory has already
     * been asked for. This is the one that keeps it from being asked for at all.
     */
    private fun readBounded(
        context: Context,
        resolver: android.content.ContentResolver,
        uri: Uri
    ): ByteArray {
        if (tooLargeToSend(sizeOf(context, uri))) throw TooLarge()
        return readBytes(resolver, uri)
    }

    private fun readBytes(resolver: android.content.ContentResolver, uri: Uri): ByteArray =
        resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("cannot read $uri")

    /** Decodes at a reduced sample size, then recompresses. Null if it is not an image. */
    private fun downscale(resolver: android.content.ContentResolver, uri: Uri): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_IMAGE_EDGE)
        }
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
