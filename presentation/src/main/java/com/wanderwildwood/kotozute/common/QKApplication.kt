/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.wanderwildwood.kotozute.common

import com.wanderwildwood.kotozute.feature.signal.SignalStreamService
import com.wanderwildwood.kotozute.signalstore.SignalForeground
import com.wanderwildwood.kotozute.worker.SignalSyncWorker
import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.os.Bundle
import androidx.emoji2.bundled.BundledEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import com.uber.rxdogtag.RxDogTag
import com.uber.rxdogtag.autodispose.AutoDisposeConfigurer
import dagger.android.AndroidInjector
import dagger.android.DispatchingAndroidInjector
import dagger.android.HasActivityInjector
import dagger.android.HasBroadcastReceiverInjector
import dagger.android.HasServiceInjector
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.util.CrashLog
import com.wanderwildwood.kotozute.common.util.FileLoggingTree
import com.wanderwildwood.kotozute.injection.AppComponentManager
import com.wanderwildwood.kotozute.injection.appComponent
import com.wanderwildwood.kotozute.interactor.SpeakThreads
import com.wanderwildwood.kotozute.manager.ReferralManager
import com.wanderwildwood.kotozute.migration.QkMigration
import com.wanderwildwood.kotozute.BuildConfig
import com.wanderwildwood.kotozute.migration.QkRealmMigration
import com.wanderwildwood.kotozute.util.NightModeManager
import com.wanderwildwood.kotozute.worker.HousekeepingWorker
import com.wanderwildwood.kotozute.worker.UpdateCheckWorker
import io.realm.Realm
import io.realm.RealmConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import com.wanderwildwood.kotozute.common.util.LibsignalSmokeTest
import com.wanderwildwood.kotozute.common.util.RealmEncryption

class QKApplication : Application(), HasActivityInjector, HasBroadcastReceiverInjector, HasServiceInjector {

    /**
     * Inject these so that they are forced to initialize
     */
    @Suppress("unused")
    @Inject lateinit var qkMigration: QkMigration

    @Inject lateinit var dispatchingActivityInjector: DispatchingAndroidInjector<Activity>
    @Inject lateinit var dispatchingBroadcastReceiverInjector: DispatchingAndroidInjector<BroadcastReceiver>
    @Inject lateinit var dispatchingServiceInjector: DispatchingAndroidInjector<Service>
    @Inject lateinit var fileLoggingTree: FileLoggingTree
    @Inject lateinit var nightModeManager: NightModeManager
    @Inject lateinit var realmMigration: QkRealmMigration
    @Inject lateinit var referralManager: ReferralManager
    @Inject lateinit var workerFactory: WorkerFactory
    @Inject lateinit var prefs: com.wanderwildwood.kotozute.util.Preferences
    @Inject lateinit var signalRepo: com.wanderwildwood.kotozute.repository.SignalRepository
    @Inject lateinit var signalNotifications: com.wanderwildwood.kotozute.feature.signal.SignalNotifications
    @Inject lateinit var messageRepo: com.wanderwildwood.kotozute.repository.MessageRepository

    override fun onCreate() {
        super.onCreate()

        // set translated "no messages" string for speakThreads interactor
        SpeakThreads.setNoMessagesString(getString(R.string.speak_no_messages))

        // ⚠ Before `appComponent.inject`, and that is the point. Dagger builds the Signal
        // repository while injecting, which opens the encrypted protocol store, which runs any
        // pending schema migration -- all of it *before* a tree exists to hear it. Measured on
        // hardware: the store is keyed 1.8 seconds before the first line that reaches logcat.
        //
        // So schema v32 migrated a live store on a Kompakt and said nothing, in a database
        // whose own documentation says an unhandled upgrade must be loud. The loud part still
        // works -- a failed migration throws and takes startup with it -- but the record of a
        // *successful* one was unreachable in a release build, which is the only build on a
        // phone.
        //
        // The console tree needs nothing, so it goes first and the migration has somewhere to
        // land. The file tree still needs `fileLoggingTree` injected, so it follows below.
        Timber.plant(Timber.DebugTree())

        // ⚠ Before anything else can die. There is no crash reporting in this app on purpose,
        // which leaves a crash on somebody else's phone invisible -- "it closed itself" is the
        // whole of the evidence they can give. This writes the trace to private storage on the
        // dying thread, where the person can read it and decide whether to send it; see
        // [CrashLog]. Installed here so a failure during the rest of onCreate is caught too.
        CrashLog.install(this, BuildConfig.VERSION_NAME)

        AppComponentManager.init(this)
        appComponent.inject(this)

        // ⚠ First, before anything that might have something to say. This used to sit near the
        // end of onCreate, after the Signal socket had been started and the first contacts and
        // configuration requests had gone -- so with no tree planted, every one of those log
        // calls went nowhere. In a release build that is the only channel there is, and the
        // lines lost were exactly the ones about what this device asks the account for at
        // startup: the hardest part of the app to reason about and the part that logs the most
        // about itself.
        //
        // Found by reading a log that was missing a line the code plainly writes, and nearly
        // explained away as an R8 artefact instead.
        //
        // It needs `appComponent.inject` above it for `fileLoggingTree`, and nothing else --
        // the console half is already planted above, before injection, so that the protocol
        // store's migration has somewhere to log.
        Timber.plant(fileLoggingTree)

        Realm.init(this)

        // One builder, used three times: to read the plaintext database during the one-time
        // conversion, to prove the encrypted copy opens, and to install the real thing. They
        // have to agree on schema and migration or the conversion would be reading a
        // different database from the one the app goes on to use.
        val realmConfig = {
            RealmConfiguration.Builder()
                    .compactOnLaunch()
                    .migration(realmMigration)
                    .schemaVersion(QkRealmMigration.SCHEMA_VERSION)
        }

        val key = RealmEncryption.keyOrNull(this)
        if (key != null) RealmEncryption.encryptExistingRealm(this, key, realmConfig)

        // Ask what is actually on disk rather than assuming the conversion ran. It declines
        // rather than risks anything, and opening a plaintext file with a key fails just as
        // surely as opening an encrypted one without.
        Realm.setDefaultConfiguration(
                realmConfig()
                        .apply { if (key != null && RealmEncryption.isEncrypted(this@QKApplication)) encryptionKey(key) }
                        .build()
        )

        // A keystore that lost its key leaves a database nobody can read. Both rails can be
        // filled again from elsewhere, so starting over beats an app that will not open.
        //
        // ⚠ **Except when the file is only newer than this build.** That failure looks
        // identical here and is the opposite case: nothing is damaged, and installing the
        // build that wrote it gets everything back. Discarding is irreversible and the Signal
        // history has no second copy, so a database that can still be opened is never deleted
        // -- this refuses to start instead, which is a state somebody can get out of.
        //
        // The throwable is logged either way. It was swallowed before, so after the fact
        // there was no way to tell a lost keystore from an older APK being installed.
        runCatching { Realm.getDefaultInstance().use { it.isEmpty } }.onFailure { failure ->
            val onDisk = RealmEncryption.versionRefusedAsNewer(failure)
                ?: RealmEncryption.schemaVersionOnDisk(key)
            if (onDisk != null && onDisk > QkRealmMigration.SCHEMA_VERSION) {
                Timber.e(
                    failure,
                    "realm: the database is at v%d and this build understands v%d -- keeping it, " +
                        "install the newer build to read it again",
                    onDisk, QkRealmMigration.SCHEMA_VERSION
                )
                throw IllegalStateException(
                    "This database was written by a newer version of this app (v$onDisk; " +
                        "this build reads v${QkRealmMigration.SCHEMA_VERSION}). Install that " +
                        "version again -- nothing has been deleted."
                )
            }
            Timber.e(failure, "realm: cannot open the database; discarding it")
            RealmEncryption.discardUnreadableRealm(this)
            val fresh = RealmEncryption.keyOrNull(this)
            if (fresh != null) RealmEncryption.encryptExistingRealm(this, fresh, realmConfig)
            Realm.setDefaultConfiguration(
                    realmConfig().apply { if (fresh != null) encryptionKey(fresh) }.build()
            )
        }

        qkMigration.performMigration()

        // The relay can't survive process death, so bring it back if it was left on.
        // This is what makes the desktop URL keep working across reboots and app
        // updates without having to re-toggle anything.
        com.wanderwildwood.kotozute.feature.desktopsync.DesktopSyncService.restoreIfEnabled(this, prefs)

        // Without this the Signal stream only ran while its screen was open, so a
        // message arriving with the app closed was not picked up until the next time
        // someone went looking -- which defeats the point of holding the stream open at all.
        // Subscribed unconditionally. Gating this on the preference meant that switching
        // Signal on for the first time started the stream but left nothing listening to
        // announce what arrived -- silent until the next launch, which is exactly the
        // session someone has just finished setting it up in. Posting is still gated,
        // inside the notifier.
        // Anything still claiming to be sending cannot be: whatever was sending it is gone with
        // the process that started it. Left alone the row says "Sending…" for the life of the
        // install, and the retry the conversation offers is only for a *failed* message -- so
        // there is nothing to press and no sign anything went wrong. Marked failed here, which
        // is what makes it retryable.
        //
        // Off the main thread, and never able to stop the app starting, for the same reason
        // everything below is guarded.
        Thread {
            runCatching { messageRepo.failStuckSends() }
                .onFailure { Timber.w(it, "could not check for messages stuck sending") }
        }.also { it.isDaemon = true }.start()

        // Signal is a feature of this app; SMS is the app. Nothing in here may be allowed to
        // stop the application being created, because a process that fails to start cannot
        // receive a text either -- which is exactly what happened when the stream service
        // threw on a background start and took every incoming SMS down with it.
        runCatching {
        signalNotifications.start()
        if (prefs.signalEnabled.get()) {
            signalRepo.startStream()
            // The stream above is a thread in this process and dies with it, which is for
            // however long Android allows. These two decide what happens after that: the
            // service holds the process open if it was asked to, and the worker bounds the
            // delay if it was not. Both are idempotent, and this runs on every process start
            // -- including the one a BOOT_COMPLETED broadcast creates, which is how Signal
            // comes back after the phone has been off.
            SignalStreamService.sync(this, prefs.signalKeepConnected.get())
        }
        }.onFailure { Timber.w(it, "signal: startup failed, carrying on without it") }

        // Disappearing messages have to be swept here, because the phone's copy is the only
        // one there is -- and without this it is the copy that outlives the timer. Reads
        // already hide an expired message, so this is about not keeping it on disk after it
        // stopped being shown.
        //
        // A minute, not the six-hour attachment pass: a Signal timer can be thirty seconds.
        GlobalScope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { signalRepo.purgeExpired() }
                    // The loop is the recovery: this runs again in a minute, so a sweep that
                    // throws delays a disappearing message by one pass rather than leaving it
                    // for ever. Never allowed to break the loop, which would leave every
                    // later expiry unswept too.
                    .onFailure { Timber.w(it, "signal: expiry sweep; the next pass sweeps again") }
                delay(60_000)
            }
        }

        // Attachments that did not arrive the first time. Frequent, because somebody is
        // waiting on a picture and the window is only a day; cheap, because it is one indexed
        // Realm query that finds nothing almost every time. A short pause first so it does not
        // race the socket it needs.
        GlobalScope.launch(Dispatchers.IO) {
            delay(90_000)
            while (true) {
                runCatching { signalRepo.retryPendingAttachments() }
                    // Same shape as the expiry loop above: this *is* the retry, so failing it
                    // costs one ten-minute round and nothing else, as long as the throw never
                    // escapes and ends the loop.
                    .onFailure { Timber.w(it, "signal attachment: retry pass; the next pass retries") }
                delay(10 * 60_000L)
            }
        }

        // The backstop behind that one. Deleting a message asks for its files by name, and a
        // name can be lost -- a row whose attachment list will not parse, a crash between the
        // row going and the file going, a download that lands after its message was withdrawn.
        // This asks the opposite question: what is on disk that no message claims. It is what
        // makes "the picture goes with the words" true rather than usually true.
        //
        // Six hours, and once at startup after a pause. It walks every message row, which is
        // not a thing to do every minute, and an orphan costs only disk until the next pass.
        // Upstream runs the same sweep from a job with a one-day lifespan
        // (`DeleteAbandonedAttachmentsJob`); there is no job queue here, so it is a loop.
        GlobalScope.launch(Dispatchers.IO) {
            delay(2 * 60_000)
            while (true) {
                runCatching { signalRepo.purgeAbandonedAttachments() }
                    .onFailure { Timber.w(it, "signal: abandoned attachment sweep") }
                delay(6 * 60 * 60_000L)
            }
        }

        GlobalScope.launch(Dispatchers.IO) {
            referralManager.trackReferrer()
        }

        nightModeManager.updateCurrentTheme()

        signalRepo.refresh()

        // Debug only now. This ran in release to answer one question -- whether libsignal's
        // native half survives R8 and loads on the phone -- and that question is answered: it
        // links, loads and does curve arithmetic on the device. Keeping it in a shipped build
        // would mean generating a throwaway identity key on every single launch, and writing
        // the shape of the protocol store into the log, to re-prove something already known.
        // It stays for debug builds, where it is still the fastest way to find out that a
        // dependency bump has broken the native path.
        if (BuildConfig.DEBUG) LibsignalSmokeTest.run(this)

        // configure emoji compatibility with bundled package
        // (bundled library works with no play-services/gsm os versions)
        EmojiCompat.init(BundledEmojiCompatConfig(this)
            .registerInitCallback(object: EmojiCompat.InitCallback() {
                override fun onInitialized() {
                    super.onInitialized()
                    Timber.v("bundled emojicompat initialized")
                }

                override fun onFailed(throwable: Throwable?) {
                    super.onFailed(throwable)
                    Timber.e("bundled emojicompat initialization failed")
                }
            })
        )

        // rxdogtag provides 'look-back' for exceptions in rxjava2 'chains'
        RxDogTag.builder()
                .configureWith(AutoDisposeConfigurer::configure)
                .install()

        // init work manager with custom factory supporting dagger/injection capability
        WorkManager.initialize(
            this,
            Configuration.Builder().setWorkerFactory(workerFactory).build()
        )

        // register, or re-register, housekeeping work manager
        HousekeepingWorker.register(applicationContext)
        UpdateCheckWorker.register(applicationContext)

        // Down here, not up with the rest of the Signal setup: WorkManager's own initialiser
        // is disabled in the manifest and it is built by hand just above, so asking for
        // getInstance() any earlier throws and takes the whole application down with it.
        SignalSyncWorker.sync(applicationContext, prefs.signalEnabled.get())

        // Whether anything of this app is on screen, for the one decision that needs it: how
        // often the Signal socket sends a keepalive. Signal reads the same fact from
        // `AppForegroundObserver` and halves the rate when nobody is looking, which on a phone
        // with no push -- where that socket is the only way a message arrives -- is most of
        // the day. Counted rather than flagged, because a configuration change stops one
        // activity and starts the next.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                started++
                SignalForeground.set(true)
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0) SignalForeground.set(false)
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    override fun activityInjector(): AndroidInjector<Activity> {
        return dispatchingActivityInjector
    }

    override fun broadcastReceiverInjector(): AndroidInjector<BroadcastReceiver> {
        return dispatchingBroadcastReceiverInjector
    }

    override fun serviceInjector(): AndroidInjector<Service> {
        return dispatchingServiceInjector
    }

}