package com.wanderwildwood.kotozute.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.wanderwildwood.kotozute.manager.NotificationManager
import com.wanderwildwood.kotozute.repository.UpdateCheck
import com.wanderwildwood.kotozute.repository.UpdateRepository
import com.wanderwildwood.kotozute.util.Preferences
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Asks, every so often, whether a newer build has been published.
 *
 * Only ever says so. It never downloads and never installs: the settings row is where a person
 * agrees to that, and a background task that replaced the app on its own would be doing
 * something nobody asked it for.
 *
 * Six hours is Signal's own interval for the same job, and it is the right order of magnitude
 * for a thing whose answer changes at most a few times a day.
 */
class UpdateCheckWorker(appContext: Context, workerParams: WorkerParameters)
    : Worker(appContext, workerParams) {

    lateinit var updateRepo: UpdateRepository
    lateinit var notificationManager: NotificationManager
    lateinit var prefs: Preferences

    companion object {
        private val WORKER_TAG: String = UpdateCheckWorker::class.java.simpleName

        fun register(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORKER_TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequest.Builder(UpdateCheckWorker::class.java, 6, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            // Nobody needs to hear about a new version badly enough to spend
                            // the last of a battery finding out.
                            .setRequiresBatteryNotLow(true)
                            .build()
                    )
                    .addTag(WORKER_TAG)
                    .build()
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORKER_TAG)
        }

        /**
         * Whether [published] is far enough ahead of [running] to be worth interrupting someone.
         *
         * A patch is not. This app has tagged sixty-six of them inside one minor version, six
         * in a day at its busiest, and a notification for each would train the reader to swipe
         * the next one away without looking -- which costs most in the release where something
         * genuinely had to be said. The settings row still offers every patch to anyone who
         * goes and asks.
         */
        internal fun worthAnnouncing(published: String, running: String): Boolean {
            val new = published.split('.').map { it.toIntOrNull() ?: 0 }
            val old = running.split('.').map { it.toIntOrNull() ?: 0 }
            val newMajor = new.getOrNull(0) ?: 0
            val oldMajor = old.getOrNull(0) ?: 0
            if (newMajor != oldMajor) return newMajor > oldMajor
            return (new.getOrNull(1) ?: 0) > (old.getOrNull(1) ?: 0)
        }
    }

    override fun doWork(): Result {
        val outcome = runBlocking { updateRepo.check() }

        if (outcome !is UpdateCheck.Available) {
            // Current, unreachable, or not a release build. None of those is worth a retry:
            // the next run is six hours away and the answer keeps.
            return Result.success()
        }

        val running = runCatching {
            applicationContext.packageManager
                .getPackageInfo(applicationContext.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")

        if (!worthAnnouncing(outcome.version, running)) {
            Timber.d("${outcome.version} is published, but it is only a patch ahead of $running")
            return Result.success()
        }

        if (prefs.updateNotified.get() == outcome.version) {
            Timber.d("Already said that ${outcome.version} is out")
            return Result.success()
        }

        notificationManager.notifyUpdateAvailable(outcome.version)
        prefs.updateNotified.set(outcome.version)
        return Result.success()
    }

}
