package com.wanderwildwood.kotozute.worker

import android.content.Context
import androidx.work.BackoffPolicy
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
        // Key maintenance rides the same schedule. It is cheap when nothing is owed, and it
        // has to happen somewhere that runs even when the listen loop is healthy -- which is
        // exactly when syncNow does nothing.
        //
        // ⚠ Above the "keep connected" check, not below it. It was below, which meant the one
        // phone guaranteed to be reachable -- the one holding the socket open all day -- was
        // the one that never topped up its keys or re-declared what it can do. Nothing about
        // either job duplicates the stream; that is only true of syncNow.
        runCatching { signalRepo.maintainKeys() }
            .onFailure { Timber.w(it, "signal: key maintenance could not run") }

        // With the service running there is a live stream already; syncing underneath it
        // would only duplicate work.
        if (prefs.signalKeepConnected.get()) return Result.success()

        return runCatching { signalRepo.syncNow() }
            .fold(
                onSuccess = {
                    // Drawing level is the point of the round, so a round that stopped short
                    // asks for another rather than leaving the phone behind for the next
                    // fifteen minutes. WorkManager backs this off on its own and the request
                    // already carries a network constraint, so it is a retry, not a spin.
                    when (signalRepo.lastSyncCaughtUp()) {
                        true -> Result.success()
                        false -> Result.retry()
                    }
                },
                onFailure = { t ->
                    // Still not a retry. A host that is off is the ordinary case and stays
                    // off for hours; asking WorkManager to back off over it would push the
                    // next attempt further out than the fifteen-minute round it replaced,
                    // which is the opposite of the point. The round is the right instrument
                    // for "come back later"; retry is for "come back now".
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
                    // Linear and short, because the only thing that asks for a retry is a
                    // catch-up that stopped short of a bridge which is up and holding more.
                    // That wants trying again in a moment, not in an hour.
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
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
