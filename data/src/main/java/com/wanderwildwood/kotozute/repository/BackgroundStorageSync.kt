package com.wanderwildwood.kotozute.repository

import java.util.concurrent.TimeUnit

/**
 * Whether a read of the account's stored records that nobody asked for may go now.
 *
 * Upstream `StorageSyncJob.onRun` (0d8785bd33, "Limit storage service syncs in the
 * background"): while `AppForegroundObserver.isForegrounded()` is false and the job's priority
 * is below `PRIORITY_HIGH`, a sync within `storageServiceBackgroundSyncInterval` of the last
 * background one is skipped, `StorageServiceValues.needsSyncOnForeground` is set, and
 * `ApplicationContext.onForeground` enqueues `StorageSyncJob.forRemoteChange()` when it finds
 * that flag. Otherwise the job stamps `lastBackgroundSyncTime` (background only), clears the
 * flag and syncs.
 *
 * Here "foreground" is [com.wanderwildwood.kotozute.signalstore.SignalForeground]: one of this
 * app's activities is started. Upstream's only `PRIORITY_HIGH` sync is
 * `StorageSyncJob.forAccountRestore()`; this app's equivalent is a read a person asked for
 * (Settings, setup's contact lookup, an account setting being written), and those never come
 * through here at all.
 */
internal object BackgroundStorageSync {

    /**
     * Six hours: `RemoteConfig.storageServiceBackgroundSyncInterval`'s default (key
     * `android.storageServiceBackgroundSyncIntervalSeconds`). Copied rather than read, because
     * this app receives no remote config -- the number is upstream's.
     */
    val INTERVAL_MS: Long = TimeUnit.HOURS.toMillis(6)

    enum class Decision {
        /** On screen: read, and leave the background stamp alone. */
        RUN,

        /** Off screen and the interval is up: read, and stamp this as the last background read. */
        RUN_IN_BACKGROUND,

        /** Off screen and too soon: do not read; read on the next foreground instead. */
        DEFER
    }

    /**
     * Upstream's test, unchanged: `timeSinceLastBackgroundSync > 0 && < interval` skips. So a
     * stamp of zero (never) reads, the interval itself reads, and a stamp in the future -- a
     * clock that moved -- reads rather than waiting for the clock to catch up.
     */
    fun decide(onScreen: Boolean, lastBackgroundSync: Long, now: Long): Decision {
        if (onScreen) return Decision.RUN
        val since = now - lastBackgroundSync
        return if (since > 0 && since < INTERVAL_MS) Decision.DEFER else Decision.RUN_IN_BACKGROUND
    }
}
