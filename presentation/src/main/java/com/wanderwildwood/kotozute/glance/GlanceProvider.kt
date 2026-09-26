package com.wanderwildwood.kotozute.glance

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

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
        if (callingPackage != READER) return null
        val context = context ?: return null
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

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    companion object {
        const val READER = "com.wanderwildwood.hitome"
        private val COLUMNS = arrayOf("heading", "lead", "text", "bold")

        /** Tells Glance to read again: when a setting changes, or new lines are ready. */
        fun changed(context: Context) {
            context.contentResolver.notifyChange(Uri.parse("content://${context.packageName}.glance/lines"), null)
        }
    }
}
