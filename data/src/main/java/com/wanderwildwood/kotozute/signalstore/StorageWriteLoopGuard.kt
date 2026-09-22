package com.wanderwildwood.kotozute.signalstore

/**
 * Stops two devices undoing each other's storage writes for ever.
 *
 * **Step 4 of `docs/DECISION-storage-write.md`**, and the reason that document says it comes
 * *before* a write is ever enabled by default rather than after: a loop here is not a local
 * bug. Two clients that each disagree with what the other just wrote will rewrite the
 * account's manifest against each other as fast as the network allows, and this app would be
 * the badly-behaved one. Signal treats a detected loop as reportable at HIGH priority.
 *
 * Ported from Signal's `StorageSyncLoopDetector`, which uses two leaky buckets:
 *
 * - a **content** bucket, charged only when a write carries the same payload as a recent one.
 *   That is the shape of a genuine loop -- the same change, sent again and again, because
 *   something keeps reverting it. Small and slow to drain.
 * - a **rate** bucket, much larger, charged on every write that followed a fresh manifest.
 *   It bounds loops the content bucket cannot see, where the payload is not stable.
 *
 * ⚠ **Pure, and deliberately.** Everything here is a function of the values passed in and the
 * state handed to it, so the thing that must not be wrong can be tested without a network, an
 * account, or a second device -- none of which are available to exercise a write against.
 */
internal class StorageWriteLoopGuard(
    private val state: State,
    private val contentCapacity: Int = CONTENT_CAPACITY,
    private val contentDripMs: Long = CONTENT_DRIP_MS,
    private val rateCapacity: Int = RATE_CAPACITY,
    private val rateDripMs: Long = RATE_DRIP_MS
) {

    /** Where the guard's counters live between runs. Kept out so it can be a map in a test. */
    interface State {
        var contentLevel: Int
        var contentUpdatedAt: Long
        var rateLevel: Int
        var rateUpdatedAt: Long
        /** Most recent payload fingerprints, newest first, at most [FINGERPRINT_HISTORY]. */
        var recentFingerprints: List<Int>
    }

    sealed interface Decision {
        data object Allowed : Decision
        /** Refused, with which bucket said so and how full it was. */
        data class Denied(val cause: Cause, val level: Int) : Decision
    }

    enum class Cause {
        /** The same payload keeps going up: something is reverting it. */
        REPEATED_PAYLOAD,

        /** Too many writes too quickly, whatever they carried. */
        WRITE_RATE
    }

    /**
     * Charges a write and says whether it may go.
     *
     * ⚠ Called once per **attempt**, and the level rises here rather than on success: a write
     * that failed still cost the account a round trip, and a loop that fails every time is
     * still a loop.
     *
     * @param fingerprint of what would be inserted, or null when a write carries only deletes
     *   and so has nothing content-addressable to compare.
     * @param fetchedRemoteManifest whether this write follows a fresh read. A write that did
     *   not is not part of the read-write cycle a loop is made of.
     * @param isRetry a conflict retry, exempt entirely -- otherwise one refused write costs as
     *   much as the several attempts it takes to resolve.
     */
    fun onWriteAttempt(
        fingerprint: Int?,
        fetchedRemoteManifest: Boolean,
        isRetry: Boolean,
        now: Long
    ): Decision {
        if (isRetry) return Decision.Allowed

        val repeated = fetchedRemoteManifest &&
            fingerprint != null &&
            state.recentFingerprints.contains(fingerprint)

        if (repeated) {
            val level = contentLevel(now)
            if (level >= contentCapacity) return Decision.Denied(Cause.REPEATED_PAYLOAD, level)
        }
        if (fetchedRemoteManifest) {
            val level = rateLevel(now)
            if (level >= rateCapacity) return Decision.Denied(Cause.WRITE_RATE, level)
        }

        if (repeated) useContent(now)
        if (fetchedRemoteManifest) useRate(now)
        if (fingerprint != null) remember(fingerprint)

        return Decision.Allowed
    }

    /**
     * Gives back a write that never landed.
     *
     * ⚠ Only the content charge. The rate bucket is about how often this device asks the
     * service to do anything at all, and a failed request asked just as much as one that
     * worked.
     */
    fun onWriteFailed(now: Long) {
        val level = contentLevel(now)
        if (level > 0) {
            state.contentLevel = level - 1
            state.contentUpdatedAt = now
        }
    }

    /**
     * The account and this device now agree.
     *
     * Clears the content bucket outright: whatever was being argued over has been settled, and
     * carrying the charge forward would count a resolved disagreement against the next one.
     */
    fun onConverged() {
        state.contentLevel = 0
        state.recentFingerprints = emptyList()
    }

    // --- the buckets -------------------------------------------------------------------------

    /**
     * The level with everything that has drained since credited, without recording the drain.
     *
     * ⚠ Integer division on purpose. A partial interval drains nothing, and
     * [contentUpdatedAt] is advanced only by whole intervals in [useContent] -- otherwise a
     * bucket charged repeatedly just under the interval would never drain at all.
     */
    private fun contentLevel(now: Long): Int = drained(state.contentLevel, state.contentUpdatedAt, contentDripMs, now)

    private fun rateLevel(now: Long): Int = drained(state.rateLevel, state.rateUpdatedAt, rateDripMs, now)

    private fun drained(level: Int, updatedAt: Long, dripMs: Long, now: Long): Int {
        if (level <= 0) return 0
        // A clock that went backwards drains nothing rather than crediting a negative age.
        val elapsed = (now - updatedAt).coerceAtLeast(0)
        return (level - (elapsed / dripMs).toInt()).coerceAtLeast(0)
    }

    private fun useContent(now: Long) {
        state.contentLevel = contentLevel(now) + 1
        state.contentUpdatedAt = now
    }

    private fun useRate(now: Long) {
        state.rateLevel = rateLevel(now) + 1
        state.rateUpdatedAt = now
    }

    private fun remember(fingerprint: Int) {
        state.recentFingerprints =
            (listOf(fingerprint) + state.recentFingerprints).distinct().take(FINGERPRINT_HISTORY)
    }

    companion object {
        /** How many past payloads a write is compared against. Signal's number. */
        const val FINGERPRINT_HISTORY = 3

        /** Signal's: three repeated payloads an hour is already a loop. */
        const val CONTENT_CAPACITY = 3
        const val CONTENT_DRIP_MS = 60L * 60 * 1000

        /** Signal's: a hundred writes, draining one per ten minutes. */
        const val RATE_CAPACITY = 100
        const val RATE_DRIP_MS = 10L * 60 * 1000

        /**
         * What a write's inserts amount to, for comparing one attempt against another.
         *
         * ⚠ Sorted before hashing, because the order inserts happen to be built in is not part
         * of what the write *says* -- and an unsorted hash would make the same payload look
         * new every time, which is precisely the case the content bucket exists to catch.
         *
         * Null for a write of nothing but deletes: there is no payload to compare.
         */
        fun fingerprintOf(insertedPayloads: List<ByteArray>): Int? {
            if (insertedPayloads.isEmpty()) return null
            return insertedPayloads.map { it.contentHashCode() }.sorted().hashCode()
        }
    }
}
