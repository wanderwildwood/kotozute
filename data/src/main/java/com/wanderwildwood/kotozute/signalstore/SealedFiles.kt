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
}
