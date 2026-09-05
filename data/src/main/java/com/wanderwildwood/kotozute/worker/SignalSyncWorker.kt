package com.wanderwildwood.kotozute.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.wanderwildwood.kotozute.repository.SignalRepository
import com.wanderwildwood.kotozute.util.Preferences
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Catches Signal up when nothing is holding the connection open.
 *
 * This is the quiet half of "Keep Signal connected". With the switch off there is no
 * foreground service and no notification, so the stream lives only as long as the app's
 * process — which is not a promise. This bounds how long a message can sit unseen instead of
 * leaving it to chance.
 *
 * Fifteen minutes because that is WorkManager's floor for periodic work, and the system is
 * free to make it longer. This is a ceiling on the delay, not a schedule.
 */
class SignalSyncWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    @Inject lateinit var signalRepo: SignalRepository
    @Inject lateinit var prefs: Preferences

    override fun doWork(): Result {
        if (!prefs.signalEnabled.get()) return Result.success()
        // With the service running there is a live stream already; syncing underneath it
        // would only duplicate work.
        if (prefs.signalKeepConnected.get()) return Result.success()

        return runCatching { signalRepo.syncNow() }
            .fold(
                onSuccess = { Result.success() },
                onFailure = { t ->
                    // The bridge being unreachable is ordinary — a laptop that is off, a phone
                    // off the network — and not something to retry in a tight loop over.
                    Timber.d(t, "signal: periodic sync could not reach the bridge")
                    Result.success()
                }
            )
    }

    companion object {
        private val WORKER_TAG: String = SignalSyncWorker::class.java.simpleName

        /**
         * Scheduled whenever Signal is on, and cancelled when it is off. Left in place while
         * the foreground service runs — the worker checks and returns — so that turning the
         * switch back off does not need the schedule rebuilding.
         */
        fun sync(context: Context, signalEnabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!signalEnabled) {
                wm.cancelUniqueWork(WORKER_TAG)
                return
            }
            wm.enqueueUniquePeriodicWork(
                WORKER_TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequest.Builder(SignalSyncWorker::class.java, 15, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .addTag(WORKER_TAG)
                    .build()
            )
        }
    }
}
