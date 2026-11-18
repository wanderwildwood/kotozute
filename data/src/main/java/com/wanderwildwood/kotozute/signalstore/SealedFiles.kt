package com.wanderwildwood.kotozute.signalstore

import java.io.File

/**
 * Where this app keeps files encrypted under the attachment secret, and the one question
 * asked of all of them together.
 *
 * Two places: the attachment store, and the outbox of messages still on their way out. Both
 * are written in [AttachmentCipher]'s format under [AttachmentSecret].
 */
internal object SealedFiles {

    /** Where older builds kept attachments, in the clear. Nothing is written here now. */
    const val ATTACHMENTS_LEGACY = "signal-attachments"

    /** Where attachments are kept, each in [AttachmentCipher]'s format. */
    const val ATTACHMENTS_SEALED = "signal-attachments-sealed"

    /** One folder per unsent message; see [SignalOutbox]. */
    const val OUTBOX = "signal-outbox"

    /** Inside a message's outbox folder: its attachments, encrypted. */
    const val OUTBOX_SEALED = "sealed"

    /**
     * Whether nothing at all has been encrypted under the attachment secret yet.
     *
     * The one condition on which a new secret may be made. The format carries no
     * authentication, so a fresh secret beside files written under a lost one would read them
     * back as noise without an error; with anything already encrypted, a missing secret is a
     * fault to report and not a gap to fill.
     *
     * Asked of both places at once: an empty outbox says nothing about the attachment store.
     */
    fun nothingEncrypted(filesDir: File): Boolean {
        if (!File(filesDir, ATTACHMENTS_SEALED).list().isNullOrEmpty()) return false
        val messages = File(filesDir, OUTBOX).listFiles().orEmpty()
        return messages.none { !File(it, OUTBOX_SEALED).list().isNullOrEmpty() }
    }

    /** How many files are encrypted, in both places together. */
    fun count(filesDir: File): Int {
        val stored = File(filesDir, ATTACHMENTS_SEALED).list()?.size ?: 0
        val unsent = File(filesDir, OUTBOX).listFiles().orEmpty()
            .sumOf { File(it, OUTBOX_SEALED).list()?.size ?: 0 }
        return stored + unsent
    }

    /**
     * Removes every encrypted file, in both places; for when their secret is lost for good
     * and nothing can read them. Files kept in the clear by older builds are left. An unsent
     * message whose attachments go is then one that cannot be sent again, and says so.
     *
     * @return true when nothing encrypted is left.
     */
    fun removeAll(filesDir: File): Boolean {
        File(filesDir, ATTACHMENTS_SEALED).listFiles().orEmpty().forEach { it.deleteRecursively() }
        File(filesDir, OUTBOX).listFiles().orEmpty().forEach { File(it, OUTBOX_SEALED).deleteRecursively() }
        return nothingEncrypted(filesDir)
    }
}
