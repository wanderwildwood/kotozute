package com.wanderwildwood.kotozute.signalstore

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream

/**
 * An export the reader picked with the document picker.
 *
 * Read through DocumentsContract rather than a DocumentFile tree: one cursor lists a whole
 * directory, where DocumentFile asks the provider again for every file it returns. An export
 * of a real history has thousands of attachments in it, and the difference is the whole of
 * how long the folder takes to open.
 */
internal class TreeExportSource(
    private val context: Context,
    private val tree: Uri
) : SignalExportSource {

    private data class Child(val id: String, val name: String, val size: Long, val isDir: Boolean)

    private val root: String by lazy { DocumentsContract.getTreeDocumentId(tree) }

    private val top: List<Child> by lazy { children(root) }

    private fun children(parent: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Child(
                            id = cursor.getString(0),
                            name = cursor.getString(1).orEmpty(),
                            size = if (cursor.isNull(2)) 0L else cursor.getLong(2),
                            isDir = cursor.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR
                        )
                    )
                }
            }
        }.orEmpty()
    }

    private fun open(id: String): InputStream =
        context.contentResolver
            .openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))
            ?: throw java.io.IOException("could not read $id")

    private fun main(): Child? =
        top.firstOrNull { !it.isDir && it.name == DirectoryExportSource.MAIN }

    override fun isExport(): Boolean = main() != null

    override fun <T> readLines(consume: (Sequence<String>) -> T): T {
        val main = main() ?: throw SignalHistoryImporter.NotAnExport()
        return open(main.id).bufferedReader().use { reader -> consume(reader.lineSequence()) }
    }

    override fun attachments(): List<SignalExportSource.Entry> {
        val files = top.firstOrNull { it.isDir && it.name == DirectoryExportSource.FILES }
            ?: return emptyList()
        return children(files.id).filter { !it.isDir }.map { child ->
            object : SignalExportSource.Entry {
                override val name: String = child.name
                override val size: Long = child.size
                override fun open(): InputStream = open(child.id)
            }
        }
    }
}
