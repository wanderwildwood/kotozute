package com.wanderwildwood.kotozute.signalstore

/**
 * Takes the media root backup key out of the plain preferences file an older build kept it in
 * and puts it in the encrypted protocol store, where the account's other keys are.
 *
 * Signal Android keeps this key in its encrypted key-value store
 * (`BackupValues.mediaRootBackupKey`, in `SignalStore`); the protocol store is this app's
 * equivalent, and `account_keys` is where it sits beside the entropy pool.
 *
 * Written as a function over four small operations so the order can be tested without a
 * database or a preferences file:
 *
 * 1. Nothing in the old place: nothing to do.
 * 2. The encrypted store has no key yet: write the old one there and **read it back**. Only a
 *    key that came back the same counts as moved.
 * 3. Remove the old one.
 *
 * A stop between 2 and 3 leaves the key in both places until the next run, which finds the
 * encrypted store already holding one and goes straight to 3. The key in the encrypted store
 * is never overwritten from the old place: if the two ever differed, the one already in use
 * for linking devices is the encrypted one.
 */
internal object MediaRootKeyMove {

    enum class Result {
        /** There was nothing in the old place. */
        NOTHING_TO_MOVE,
        /** The key is in the encrypted store and the old copy is gone. */
        MOVED,
        /** The encrypted store would not take it. The old copy is left, to try again. */
        NOT_STORED,
        /** The key is in the encrypted store but the old copy would not delete. Tried again next time. */
        OLD_COPY_KEPT
    }

    /** The length of a media root backup key, as `MediaRootBackupKey` requires. */
    const val KEY_BYTES = 32

    /**
     * @param readOld the key in the old place, or null. Null too for one that will not decode.
     * @param oldPresent whether anything at all is in the old place, decodable or not.
     * @param readStored the key in the encrypted store, or null.
     * @param store writes the key to the encrypted store.
     * @param removeOld removes the old copy, returning whether it is gone.
     */
    fun run(
        oldPresent: () -> Boolean,
        readOld: () -> ByteArray?,
        readStored: () -> ByteArray?,
        store: (ByteArray) -> Unit,
        removeOld: () -> Boolean
    ): Result {
        if (!oldPresent()) return Result.NOTHING_TO_MOVE
        val old = readOld()?.takeIf { it.size == KEY_BYTES }
        if (old != null && readStored() == null) {
            store(old)
            if (readStored()?.contentEquals(old) != true) return Result.NOT_STORED
        }
        // An old value that was not a key at all is simply removed: the build that wrote it
        // would have replaced it with a fresh one the next time it was wanted, too.
        return if (removeOld()) Result.MOVED else Result.OLD_COPY_KEPT
    }
}
