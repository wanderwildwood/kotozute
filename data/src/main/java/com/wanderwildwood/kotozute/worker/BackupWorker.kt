package com.wanderwildwood.kotozute.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.wanderwildwood.kotozute.manager.NotificationManager
import com.wanderwildwood.kotozute.repository.BackupRepository
import com.wanderwildwood.kotozute.util.MessageStoreGate
import com.wanderwildwood.kotozute.util.Preferences
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * An automatic backup: the same file, in the same folder, as "Backup now", on a daily or weekly
 * schedule, followed by removing this app's older backups beyond the number to keep.
 *
 * It never asks for a retry. A failed one says so in a notification and the next one runs as
 * scheduled; and when it fails nothing is deleted, so a folder that stopped working does not
 * also lose the backups that are still in it.
 */
class BackupWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    @Inject lateinit var backupRepo: BackupRepository
    @Inject lateinit var prefs: Preferences
    @Inject lateinit var notificationManager: NotificationManager

    override fun doWork(): Result {
        if (prefs.backupFrequency.get() == FREQUENCY_OFF) return Result.success()
        // With the message database refused the one in use is empty: a backup of it would be
        // empty, and pruning after it would push real backups out for nothing.
        if (MessageStoreGate.blocked) return Result.success()
        // "Backup now", or a restore, is under way. Skip this round.
        if (backupRepo.isBackupRunning()) return Result.success()

        val written = runCatching { backupRepo.performBackup() }
            .onFailure { Timber.w(it, "automatic backup failed") }
            .getOrNull()

        if (written == null) {
            notificationManager.notifyBackupFailed()
            return Result.success()
        }

        runCatching { backupRepo.pruneBackups(prefs.backupKeep.get(), written) }
            .onFailure { Timber.w(it, "pruning old backups failed") }
        return Result.success()
    }

    companion object {
        const val FREQUENCY_OFF = 0
        const val FREQUENCY_DAILY = 1
        const val FREQUENCY_WEEKLY = 2

        private const val WORK_NAME = "BackupWorker"

        /** Puts the schedule in line with [frequency]: cancels it when off. Survives a reboot (WorkManager). */
        fun sync(context: Context, frequency: Int) {
            val manager = WorkManager.getInstance(context)
            val days = when (frequency) {
                FREQUENCY_DAILY -> 1L
                FREQUENCY_WEEKLY -> 7L
                else -> {
                    manager.cancelUniqueWork(WORK_NAME)
                    return
                }
            }
            manager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequest.Builder(BackupWorker::class.java, days, TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                    .build()
            )
        }
    }
}
