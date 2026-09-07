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
import com.wanderwildwood.kotozute.worker.SignalSyncWorker
import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
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
import com.wanderwildwood.kotozute.common.util.FileLoggingTree
import com.wanderwildwood.kotozute.injection.AppComponentManager
import com.wanderwildwood.kotozute.injection.appComponent
import com.wanderwildwood.kotozute.interactor.SpeakThreads
import com.wanderwildwood.kotozute.manager.ReferralManager
import com.wanderwildwood.kotozute.migration.QkMigration
import com.wanderwildwood.kotozute.migration.QkRealmMigration
import com.wanderwildwood.kotozute.util.NightModeManager
import com.wanderwildwood.kotozute.worker.HousekeepingWorker
import io.realm.Realm
import io.realm.RealmConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import com.wanderwildwood.kotozute.common.util.LibsignalSmokeTest
import com.wanderwildwood.kotozute.common.util.SignalLinkTrial
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

    override fun onCreate() {
        super.onCreate()

        // set translated "no messages" string for speakThreads interactor
        SpeakThreads.setNoMessagesString(getString(R.string.speak_no_messages))

        AppComponentManager.init(this)
        appComponent.inject(this)

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
        runCatching { Realm.getDefaultInstance().use { it.isEmpty } }.onFailure {
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
        // someone went looking -- which defeats the point of the bridge pushing at all.
        // Subscribed unconditionally. Gating this on the preference meant that switching
        // Signal on for the first time started the stream but left nothing listening to
        // announce what arrived -- silent until the next launch, which is exactly the
        // session someone has just finished setting it up in. Posting is still gated,
        // inside the notifier.
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

        // Disappearing messages have to be swept here, not only on the bridge. The bridge
        // deletes its own row on time, but the phone's copy is the one anybody reads -- and
        // without this it is the copy that outlives the timer. Reads already hide an expired
        // message, so this is about not keeping it on disk after it stopped being shown.
        //
        // A minute, not the six-hour attachment pass: a Signal timer can be thirty seconds.
        GlobalScope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { signalRepo.purgeExpired() }
                    .onFailure { Timber.w(it, "signal: expiry sweep") }
                delay(60_000)
            }
        }

        GlobalScope.launch(Dispatchers.IO) {
            referralManager.trackReferrer()
        }

        nightModeManager.updateCurrentTheme()

        // configure timber logging
        Timber.plant(Timber.DebugTree(), fileLoggingTree)

        // EXPERIMENT (signal-on-the-phone branch): after planting, deliberately. Run before
        // it, Timber has no trees and the answer goes nowhere -- which is how the first
        // attempt at this reported nothing at all.
        // EXPERIMENT: release too, on this branch only. R8 strips what it cannot see being
        // used, and a native method reached over JNI is exactly that -- so whether libsignal
        // survives minification is a question the debug build cannot answer.
        LibsignalSmokeTest.run(this)
        SignalLinkTrial.runIfRequested(this, "kotozute")

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

        // Down here, not up with the rest of the Signal setup: WorkManager's own initialiser
        // is disabled in the manifest and it is built by hand just above, so asking for
        // getInstance() any earlier throws and takes the whole application down with it.
        SignalSyncWorker.sync(applicationContext, prefs.signalEnabled.get())
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