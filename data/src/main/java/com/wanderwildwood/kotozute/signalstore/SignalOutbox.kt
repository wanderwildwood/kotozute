package com.wanderwildwood.kotozute.signalstore

import java.io.File

/**
 * What a message on its way out carries, kept on disk until it has gone.
 *
 * The composer hands a send its attachments as data URIs in memory. That is enough while the
 * process lives; a send cut off by the process dying has to be sent again from somewhere, and
 * Signal sends it again from its own attachment table (`RetryPendingSendsJob`). This is the
 * nearest thing: one folder per message, one file per attachment, gone once the message is.
 *
 * **Encrypted, as the attachment store is.** Each attachment is a file named by its place in
 * the message (`0`, `1`, ...) inside the message's `sealed` folder, in [AttachmentCipher]'s
 * format under the same secret. Older builds wrote them in the clear, directly in the
 * message's folder; those are still read, and [encryptStored] moves them. A message's folder
 * is an [AttachmentVault] of its own, so the move is the same one, with the same order: the
 * plaintext goes last, after its replacement has been read back.
 *
 * @param readSecret the attachment secret, or null when it cannot be had. Never creates one.
 * @param writeSecret the secret for writing; throws when it cannot be had.
 * @param nothingEncrypted see [AttachmentVault]; null in tests, where one folder is all there is.
 * @param checkpoint for tests, which stop a move part way.
 */
internal class SignalOutbox(
    private val root: File,
    private val readSecret: () -> ByteArray?,
    private val writeSecret: (mayCreate: () -> Boolean) -> ByteArray,
    private val nothingEncrypted: (() -> Boolean)? = null,
    private val checkpoint: (AttachmentVault.Step) -> Unit = {}
) {

    /** An attachment is on disk and cannot be read. Not "gone": nothing was lost. */
    class Unreadable(message: String) : IllegalStateException(message)

    /**
     * Writes a message's attachments down, encrypted.
     *
     * @throws IllegalStateException when the secret cannot be had. Nothing is written in the
     *   clear instead; the caller treats it as it does any failure to write: not sent.
     */
    fun save(messageId: String, attachments: List<String>) {
        if (attachments.isEmpty()) return
        val dir = dirFor(messageId)
        dir.mkdirs()
        val vault = vaultFor(dir)
        attachments.forEachIndexed { i, uri -> vault.write("$i", uri.byteInputStream()) }
    }

    /**
     * The attachments, in the order they were given, or null when a message that had some no
     * longer does -- which is a message that cannot honestly be sent again.
     *
     * Each place in the message is read once, from whichever form it is in, so a move stopped
     * part way gives the same list as before it and after it: never one short, never one
     * twice. Anything in the folder that is not a place number -- the working file of a move
     * that was cut off -- is not an attachment and is passed over.
     *
     * @throws Unreadable when an attachment is there and cannot be read, which is the secret
     *   being unavailable. Deliberately not null: null means "gone", and the caller marks the
     *   message as one that can never be sent.
     */
    fun load(messageId: String, hadAttachments: Boolean): List<String>? {
        if (!hadAttachments) return emptyList()
        val vault = vaultFor(dirFor(messageId))
        val places = vault.names().mapNotNull(::placeOf).toSortedSet()
        if (places.isEmpty()) return null
        return places.map { place ->
            vault.read("$place")?.toString(Charsets.UTF_8)
                ?: throw Unreadable("an unsent message's attachment is on this phone and cannot be read")
        }
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

    /**
     * Encrypts the attachments of unsent messages that an older build wrote in the clear.
     * See [AttachmentVault.migrateAll]; safe to stop, safe to run again.
     *
     * @return how many are still in the clear afterwards.
     */
    fun encryptStored(): Int =
        root.listFiles().orEmpty().filter { it.isDirectory }.sumOf { dir ->
            val vault = vaultFor(dir)
            if (vault.plaintextCount() == 0) 0 else vault.migrateAll().left
        }

    private fun vaultFor(dir: File) = AttachmentVault(
        sealedDir = File(dir, SealedFiles.OUTBOX_SEALED),
        legacyDir = dir,
        readSecret = readSecret,
        writeSecret = writeSecret,
        checkpoint = checkpoint,
        nothingEncrypted = nothingEncrypted
    )

    /** `7` for a file named "7", and null for anything that is not exactly a place number. */
    private fun placeOf(name: String): Int? = name.toIntOrNull()?.takeIf { it >= 0 && it.toString() == name }

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
