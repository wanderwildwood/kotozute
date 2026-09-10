package com.wanderwildwood.kotozute.signalstore

import java.io.File
import java.io.OutputStream

/**
 * Where an export is written: `main.jsonl` beside a `files/` directory, the same shape the
 * importer reads. On the phone that is a folder the reader picked; in a test it is a
 * directory.
 */
internal interface SignalExportDestination {

    /** The records file, truncated. */
    fun main(): OutputStream

    /** A file under `files/`, or null if it could not be created. */
    fun file(name: String): OutputStream?

    /** What to call the folder in a sentence, once the writing is done. */
    fun name(): String
}

/** An export written into an ordinary directory. */
internal class DirectoryExportDestination(private val dir: File) : SignalExportDestination {

    private val files = File(dir, DirectoryExportSource.FILES)

    override fun main(): OutputStream {
        dir.mkdirs()
        return File(dir, DirectoryExportSource.MAIN).outputStream()
    }

    override fun file(name: String): OutputStream? {
        files.mkdirs()
        return File(files, name).outputStream()
    }

    override fun name(): String = dir.name
}
