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
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

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
        Realm.setDefaultConfiguration(RealmConfiguration.Builder()
                .compactOnLaunch()
                .migration(realmMigration)
                .schemaVersion(QkRealmMigration.SCHEMA_VERSION)
                .build())

        qkMigration.performMigration()

        // The relay can't survive process death, so bring it back if it was left on.
        // This is what makes the desktop URL keep working across reboots and app
        // updates without having to re-toggle anything.
        com.wanderwildwood.kotozute.feature.desktopsync.DesktopSyncService.restoreIfEnabled(this, prefs)

        // Without this the Signal stream only ran while its screen was open, so a
        // message arriving with the app closed was not picked up until the next time
        // someone went looking -- which defeats the point of the bridge pushing at all.
        if (prefs.signalEnabled.get()) {
            signalNotifications.start()
            signalRepo.startStream()
        }

        GlobalScope.launch(Dispatchers.IO) {
            referralManager.trackReferrer()
        }

        nightModeManager.updateCurrentTheme()

        // configure timber logging
        Timber.plant(Timber.DebugTree(), fileLoggingTree)

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