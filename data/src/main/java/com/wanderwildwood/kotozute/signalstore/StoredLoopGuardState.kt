package com.wanderwildwood.kotozute.signalstore

import android.content.Context

/**
 * The loop guard's counters, kept between runs.
 *
 * ⚠ Kept, not held in memory: the process on this phone is killed and restarted all day, and a
 * guard that forgot its buckets every time would let a loop run at full speed for as long as
 * the app kept being reopened. Upstream keeps the same counters in `SignalStore`.
 */
internal class StoredLoopGuardState(context: Context) : StorageWriteLoopGuard.State {

    private val prefs = context.getSharedPreferences("signal-storage-write-guard", Context.MODE_PRIVATE)

    override var contentLevel: Int
        get() = prefs.getInt("contentLevel", 0)
        set(value) = prefs.edit().putInt("contentLevel", value).apply()

    override var contentUpdatedAt: Long
        get() = prefs.getLong("contentUpdatedAt", 0L)
        set(value) = prefs.edit().putLong("contentUpdatedAt", value).apply()

    override var rateLevel: Int
        get() = prefs.getInt("rateLevel", 0)
        set(value) = prefs.edit().putInt("rateLevel", value).apply()

    override var rateUpdatedAt: Long
        get() = prefs.getLong("rateUpdatedAt", 0L)
        set(value) = prefs.edit().putLong("rateUpdatedAt", value).apply()

    override var recentFingerprints: List<Int>
        get() = prefs.getString("recentFingerprints", "").orEmpty()
            .split(',').mapNotNull { it.toIntOrNull() }
        set(value) = prefs.edit().putString("recentFingerprints", value.joinToString(",")).apply()
}
