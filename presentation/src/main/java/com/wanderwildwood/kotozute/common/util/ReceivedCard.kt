package com.wanderwildwood.kotozute.common.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import timber.log.Timber
import java.io.File
import java.nio.charset.Charset

/**
 * A contact card that arrived in a text, made into a file any contacts app can be handed.
 *
 * The part is read here, in this app, and a copy is written to the cache under the
 * FileProvider, rather than handing the other app an address that leads back through the
 * messages database. That address only worked when the database kept the card as a file; a
 * card kept in the message's text column, or one the other app was not allowed to reach
 * through us, opened as "this file could not be opened" in Contacts with no hint why.
 */
object ReceivedCard {

    /** Why a card could not be shown, in the order the person would want to be told. */
    enum class Failure { NOT_THERE, EMPTY, NOT_A_CARD }

    class Result(val uri: Uri?, val failure: Failure?)

    /**
     * Tidies bytes read from a part: reads UTF-16 and UTF-8 with a byte-order mark, drops the
     * mark and stray nulls, and answers null unless a vCard is in there. Always UTF-8 out.
     */
    fun clean(bytes: ByteArray?): ByteArray? {
        if (bytes == null || bytes.isEmpty()) return null
        val text = when {
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            else -> String(bytes, Charsets.UTF_8)
        }.replace("\u0000", "").trim()
        if (!text.contains("BEGIN:VCARD", ignoreCase = true)) return null
        return text.toByteArray(Charsets.UTF_8)
    }

    /** Reads the part's bytes: as a file first, and from the message's text column if it has none. */
    private fun read(context: Context, partId: Long): ByteArray? {
        val part = Uri.parse("content://mms/part/$partId")
        val fromFile = runCatching {
            context.contentResolver.openInputStream(part)?.use { it.readBytes() }
        }.onFailure { Timber.w(it, "contact card: part $partId has no file") }.getOrNull()
        if (fromFile != null && fromFile.isNotEmpty()) return fromFile
        return runCatching {
            context.contentResolver.query(part, arrayOf("text", "cl"), null, null, null)?.use { c ->
                if (!c.moveToFirst()) null else c.getString(0)?.toByteArray(Charsets.UTF_8)
            }
        }.onFailure { Timber.w(it, "contact card: part $partId has no text") }.getOrNull()
    }

    /** Disk and database work: call it off the main thread. */
    fun stage(context: Context, partId: Long): Result {
        val raw = read(context, partId) ?: return Result(null, Failure.NOT_THERE)
        if (raw.isEmpty()) return Result(null, Failure.EMPTY)
        val bytes = clean(raw) ?: return Result(null, Failure.NOT_A_CARD)
        return runCatching {
            val dir = File(context.cacheDir, "cards").apply { mkdirs() }
            val file = File(dir, "card-$partId.vcf").apply { writeBytes(bytes) }
            Result(FileProvider.getUriForFile(context, "${context.packageName}.messagesText", file), null)
        }.onFailure { Timber.w(it, "contact card: could not stage a copy") }
            .getOrElse { Result(null, Failure.NOT_THERE) }
    }
}
