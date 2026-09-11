package com.wanderwildwood.kotozute.signalstore

import java.io.File
import java.io.InputStream

/**
 * A Signal "export chat history" folder, wherever it happens to live.
 *
 * The folder is `main.jsonl` beside a `files/` directory of plaintext attachments. On the
 * phone it arrives through the document picker and is read through a content provider; in a
 * test it is a directory. The importer does not care which, and saying so here is what lets
 * the rules be tested without a device.
 */
internal interface SignalExportSource {

    /** One attachment file. [size] is what an export's own references can be matched on. */
    interface Entry {
        val name: String
        val size: Long
        fun open(): InputStream
    }

    /** True when this looks like an export at all -- that is, when main.jsonl is there. */
    fun isExport(): Boolean

    /**
     * The records, one JSON object per line, streamed.
     *
     * Streamed rather than read whole: the file is small next to the media, but a history
     * has no fixed size and this runs on a phone.
     */
    fun <T> readLines(consume: (Sequence<String>) -> T): T

    /** Everything under `files/`. Empty when an export carries no attachments, which is fine. */
    fun attachments(): List<Entry>

    /** The records file as bytes, for a reader that has to unwrap them first. */
    fun openMain(): InputStream

    /**
     * The backup header, when this folder holds a backup this app wrote rather than an
     * export Signal wrote. Null for the latter, which is how the two are told apart.
     */
    fun meta(): String?
}

/**
 * A backup this app wrote, read through the key it was locked with.
 *
 * The same records and the same files; only the bytes on disk differ, so everything that
 * reads an export reads this one too once it is unwrapped.
 */
internal class EncryptedExportSource(
    private val inner: SignalExportSource,
    key: String,
    salt: ByteArray
) : SignalExportSource {

    private val derived = SignalBackupCrypto.derive(key, salt)

    override fun isExport(): Boolean = inner.isExport()

    override fun <T> readLines(consume: (Sequence<String>) -> T): T =
        openMain().bufferedReader().use { reader -> consume(reader.lineSequence()) }

    override fun openMain(): InputStream = SignalBackupCrypto.decrypt(inner.openMain(), derived)

    override fun meta(): String? = inner.meta()

    override fun attachments(): List<SignalExportSource.Entry> =
        inner.attachments().map { entry ->
            object : SignalExportSource.Entry {
                override val name: String = entry.name
                // The sealed size, not the size the file had: a chunk carries a tag and the
                // stream carries a nonce, so an encrypted attachment is bigger than what it
                // holds. Only the name is matched on for our own backups, which is what this
                // is; a size is what Signal's own export makes us fall back to.
                override val size: Long = entry.size
                override fun open(): InputStream =
                    SignalBackupCrypto.decrypt(entry.open(), derived)
            }
        }
}

/** An export as an ordinary directory. */
internal class DirectoryExportSource(private val dir: File) : SignalExportSource {

    private val main = File(dir, MAIN)

    override fun isExport(): Boolean = main.isFile

    override fun <T> readLines(consume: (Sequence<String>) -> T): T =
        main.bufferedReader().use { reader -> consume(reader.lineSequence()) }

    override fun openMain(): InputStream = main.inputStream()

    override fun meta(): String? =
        File(dir, EncryptedExportDestination.META).takeIf { it.isFile }?.readText()

    override fun attachments(): List<SignalExportSource.Entry> {
        val files = File(dir, FILES)
        if (!files.isDirectory) return emptyList()
        return files.walkTopDown()
            .filter { it.isFile }
            .map { file ->
                object : SignalExportSource.Entry {
                    override val name: String = file.name
                    override val size: Long = file.length()
                    override fun open(): InputStream = file.inputStream()
                }
            }
            .toList()
    }

    companion object {
        const val MAIN = "main.jsonl"
        const val FILES = "files"
    }
}
