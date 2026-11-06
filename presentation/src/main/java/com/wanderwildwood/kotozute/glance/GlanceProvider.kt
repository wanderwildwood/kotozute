package com.wanderwildwood.kotozute.glance

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.security.MessageDigest

/**
 * What this app shows on the lock screen, handed to Glance (com.wanderwildwood.hitome), which
 * draws it there. The same file in every app that takes part.
 *
 * It answers Glance alone - any other caller gets nothing - and says nothing at all while this
 * app's own "on the lock screen" setting is off. One row per line: `heading` (first row only,
 * optional), `lead` (a short left column: a time, a temperature), `text`, and `bold`.
 */
abstract class GlanceProvider : ContentProvider() {

    data class Line(
        val text: String,
        val lead: String? = null,
        val bold: Boolean = false,
        val heading: String? = null,
    )

    /** This app's own switch for the lock screen. */
    protected abstract fun enabled(context: Context): Boolean

    /** The lines, top to bottom; empty for nothing to say. */
    protected abstract fun lines(context: Context): List<Line>

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val context = context ?: return null
        if (!fromGlance(context)) return null
        val cursor = MatrixCursor(COLUMNS)
        if (enabled(context)) {
            try {
                lines(context).forEach { cursor.addRow(arrayOf<Any?>(it.heading, it.lead, it.text, if (it.bold) 1 else 0)) }
            } catch (_: Exception) {
                // A line that could not be made is a line left out, never a broken lock screen.
            }
        }
        cursor.setNotificationUri(context.contentResolver, uri)
        return cursor
    }

    /**
     * Whether the caller is Glance: its package name, and signed with Glance's release certificate
     * and nothing else. A name alone is not enough - an app can take Glance's name on a phone where
     * Glance is not installed. A debuggable build of this app answers any Glance, for testing.
     */
    protected fun fromGlance(context: Context): Boolean {
        if (callingPackage != READER) return false
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return true
        return runCatching {
            val signers = context.packageManager
                .getPackageInfo(READER, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
            !signers.isNullOrEmpty() && signers.all { sha256(it.toByteArray()) == READER_CERT_SHA256 }
        }.getOrDefault(false)
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    companion object {
        const val READER = "com.wanderwildwood.hitome"

        /** SHA-256 of the certificate Glance's releases are signed with, lower-case hex. */
        const val READER_CERT_SHA256 = "8b5ad77e1b6c23ad805c7aef9864967bfefbafd442e43a472ab212f0593248b8"
        private val COLUMNS = arrayOf("heading", "lead", "text", "bold")

        /** Tells Glance to read again: when a setting changes, or new lines are ready. */
        fun changed(context: Context) {
            context.contentResolver.notifyChange(Uri.parse("content://${context.packageName}.glance/lines"), null)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
