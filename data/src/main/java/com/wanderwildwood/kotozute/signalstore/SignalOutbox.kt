package com.wanderwildwood.kotozute.signalstore

import java.io.File

/**
 * What a message on its way out carries, kept on disk until it has gone.
 *
 * The composer hands a send its attachments as data URIs in memory. That is enough while the
 * process lives; a send cut off by the process dying has to be sent again from somewhere, and
 * Signal sends it again from its own attachment table (`RetryPendingSendsJob`). This is the
 * nearest thing: one folder per message, one file per attachment, gone once the message is.
 */
internal class SignalOutbox(private val root: File) {

    fun save(messageId: String, attachments: List<String>) {
        if (attachments.isEmpty()) return
        val dir = dirFor(messageId)
        dir.mkdirs()
        attachments.forEachIndexed { i, uri -> File(dir, "$i").writeText(uri) }
    }

    /**
     * The attachments, in the order they were given, or null when a message that had some no
     * longer does -- which is a message that cannot honestly be sent again.
     */
    fun load(messageId: String, hadAttachments: Boolean): List<String>? {
        if (!hadAttachments) return emptyList()
        val files = dirFor(messageId).listFiles()?.filter { it.isFile } ?: return null
        if (files.isEmpty()) return null
        return files.sortedBy { it.name.toIntOrNull() ?: Int.MAX_VALUE }.map { it.readText() }
    }

    fun clear(messageId: String) {
        dirFor(messageId).deleteRecursively()
    }

    fun clearAll() {
        root.deleteRecursively()
    }

    /**
     * Removes every message's folder but those still on their way or not sent.
     *
     * A folder goes when its message is sent or discarded here; a message that leaves any
     * other way -- its conversation deleted, its timer run out, withdrawn -- took nothing with
     * it, and the attachment sat on disk for good. Same shape as the attachment sweep, and on
     * the same pass.
     *
     * @return how many folders went.
     */
    fun sweep(unsentIds: Set<String>): Int {
        val keep = unsentIds.map { dirFor(it).name }.toSet()
        val gone = root.listFiles()?.filter { it.isDirectory && it.name !in keep }.orEmpty()
        gone.forEach { it.deleteRecursively() }
        return gone.size
    }

    /** An id is `aci:timestamp`; a colon is not something to put in a path. */
    private fun dirFor(messageId: String) = File(root, messageId.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    companion object {
        /**
         * Upstream's window, from `getRecentPendingMessages`: sent in the last day, and not in
         * the future. Older than that, the conversation has moved on and a resend would land
         * in the middle of it; it is marked not sent instead, for a person to decide.
         */
        const val RESEND_WINDOW_MS = 24L * 60 * 60 * 1000

        fun worthResending(sentAt: Long, now: Long): Boolean =
            sentAt > now - RESEND_WINDOW_MS && sentAt < now
    }
}
