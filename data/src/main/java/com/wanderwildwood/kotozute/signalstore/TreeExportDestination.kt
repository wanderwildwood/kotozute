package com.wanderwildwood.kotozute.signalstore

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.io.OutputStream

/**
 * An export written into a folder the reader picked.
 *
 * It makes a folder of its own inside that one, named for the day. The picked folder is
 * usually somewhere like Downloads with other things already in it, and an export is
 * `main.jsonl` plus a `files/` directory -- two names general enough to land on top of
 * something that was already there.
 */
internal class TreeExportDestination(
    private val context: Context,
    tree: Uri,
    private val folderName: String
) : SignalExportDestination {

    private val root: String = DocumentsContract.getTreeDocumentId(tree)
    private val treeUri: Uri = tree

    private val folder: String by lazy {
        createDirectory(root, folderName) ?: throw IOException("could not make $folderName")
    }

    private val files: String by lazy {
        createDirectory(folder, DirectoryExportSource.FILES)
            ?: throw IOException("could not make ${DirectoryExportSource.FILES}")
    }

    private fun documentUri(id: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

    private fun createDirectory(parent: String, name: String): String? = runCatching {
        DocumentsContract.createDocument(
            context.contentResolver,
            documentUri(parent),
            DocumentsContract.Document.MIME_TYPE_DIR,
            name
        )?.let { DocumentsContract.getDocumentId(it) }
    }.getOrNull()

    private fun create(parent: String, mime: String, name: String): OutputStream? = runCatching {
        DocumentsContract.createDocument(context.contentResolver, documentUri(parent), mime, name)
            ?.let { context.contentResolver.openOutputStream(it) }
    }.getOrNull()

    // Deliberately not application/json. A document provider appends the extension its mime
    // type implies when the name does not already carry it, and there is no mime whose
    // extension is "jsonl" -- so asking for JSON wrote main.jsonl.json, which is a file the
    // importer does not look for. A backup that cannot be read back is not a backup, and
    // nothing off the device could see it: a plain directory keeps whatever name it is given.
    override fun main(): OutputStream =
        create(folder, "application/octet-stream", DirectoryExportSource.MAIN)
            ?: throw IOException("could not write ${DirectoryExportSource.MAIN}")

    // The provider may rename a file it is given -- a name it already holds gets a suffix --
    // but every name here is an attachment id, unique within one fresh folder.
    override fun file(name: String): OutputStream? =
        create(files, "application/octet-stream", name)

    override fun name(): String = folderName
}
