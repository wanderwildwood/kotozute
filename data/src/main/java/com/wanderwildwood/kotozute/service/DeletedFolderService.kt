package com.wanderwildwood.kotozute.service

import android.annotation.SuppressLint
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.wanderwildwood.kotozute.common.util.extensions.jobScheduler
import com.wanderwildwood.kotozute.interactor.PurgeDeleted
import com.wanderwildwood.kotozute.util.MessageStoreGate
import dagger.android.AndroidInjection
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Once a day, removes for good what has been in Deleted for 30 days ([PurgeDeleted]). The same
 * mechanism as [AutoDeleteService]; the app also sweeps once at start. A day late at worst.
 */
class DeletedFolderService : JobService() {

    companion object {
        private const val JOB_ID = 8120236

        /**
         * Idempotent. Only scheduled when it is not already: scheduling again restarts the
         * day, and a phone whose app starts every few hours would then never run it.
         */
        @SuppressLint("MissingPermission") // Added in [presentation]'s AndroidManifest.xml
        fun scheduleJob(context: Context) {
            if (context.jobScheduler.getPendingJob(JOB_ID) != null) return
            val periodicJob = JobInfo.Builder(JOB_ID, ComponentName(context, DeletedFolderService::class.java))
                    .setPeriodic(TimeUnit.DAYS.toMillis(1))
                    .setPersisted(true)
                    .build()
            context.jobScheduler.schedule(periodicJob)
        }
    }

    @Inject lateinit var purgeDeleted: PurgeDeleted

    private val disposables = CompositeDisposable()

    override fun onStartJob(params: JobParameters?): Boolean {
        // Locked, the database open is an empty placeholder; nothing to sweep until it opens.
        if (MessageStoreGate.blocked) return false
        AndroidInjection.inject(this)
        disposables += purgeDeleted
        purgeDeleted.execute(Unit, onError = { jobFinished(params, false) }) {
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Timber.i("Deleted sweep stopped; it runs again tomorrow")
        disposables.dispose()
        return true
    }
}
