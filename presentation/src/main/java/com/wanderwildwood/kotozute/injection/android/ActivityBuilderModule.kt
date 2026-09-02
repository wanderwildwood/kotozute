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
package com.wanderwildwood.kotozute.injection.android

import dagger.Module
import dagger.android.ContributesAndroidInjector
import com.wanderwildwood.kotozute.feature.backup.BackupActivity
import com.wanderwildwood.kotozute.feature.blocking.BlockingActivity
import com.wanderwildwood.kotozute.feature.signal.SignalConversationsActivity
import com.wanderwildwood.kotozute.feature.signal.SignalThreadActivity
import com.wanderwildwood.kotozute.feature.signal.SignalThreadInfoActivity
import com.wanderwildwood.kotozute.feature.compose.ComposeActivity
import com.wanderwildwood.kotozute.feature.compose.ComposeActivityModule
import com.wanderwildwood.kotozute.feature.contacts.ContactsActivity
import com.wanderwildwood.kotozute.feature.contacts.ContactsActivityModule
import com.wanderwildwood.kotozute.feature.conversationinfo.ConversationInfoActivity
import com.wanderwildwood.kotozute.feature.gallery.GalleryActivity
import com.wanderwildwood.kotozute.feature.gallery.GalleryActivityModule
import com.wanderwildwood.kotozute.feature.main.MainActivity
import com.wanderwildwood.kotozute.feature.main.MainActivityModule
import com.wanderwildwood.kotozute.feature.notificationprefs.NotificationPrefsActivity
import com.wanderwildwood.kotozute.feature.notificationprefs.NotificationPrefsActivityModule
import com.wanderwildwood.kotozute.feature.qkreply.QkReplyActivity
import com.wanderwildwood.kotozute.feature.qkreply.QkReplyActivityModule
import com.wanderwildwood.kotozute.feature.scheduled.ScheduledActivity
import com.wanderwildwood.kotozute.feature.scheduled.ScheduledActivityModule
import com.wanderwildwood.kotozute.feature.settings.SettingsActivity
import com.wanderwildwood.kotozute.injection.scope.ActivityScope

@Module
abstract class ActivityBuilderModule {

    @ActivityScope
    @ContributesAndroidInjector(modules = [MainActivityModule::class])
    abstract fun bindMainActivity(): MainActivity

    @ActivityScope
    @ContributesAndroidInjector(modules = [])
    abstract fun bindBackupActivity(): BackupActivity

    @ActivityScope
    @ContributesAndroidInjector(modules = [ComposeActivityModule::class])
    abstract fun bindComposeActivity(): ComposeActivity

    @ActivityScope
    @ContributesAndroidInjector(modules = [ContactsActivityModule::class])
    abstract fun bindContactsActivity(): ContactsActivity

    @ActivityScope
    @ContributesAndroidInjector(modules = [])
    abstract fun bindConversationInfoActivity(): ConversationInfoActivity

    @ActivityScope
    @ContributesAndroidInjector(modules = [GalleryActivityModule::class])
    abstract fun bindGalleryActivity(): GalleryActivity

    @ActivityScope
    @ContributesAndroidInjector(modules = [NotificationPrefsActivityModule::class])
    abstract fun bindNotificationPrefsActivity(): NotificationPrefsActivity

    @ActivityScope
    @ContributesAndroidInjector(modules = [QkReplyActivityModule::class])
    abstract fun bindQkReplyActivity(): QkReplyActivity

    @ActivityScope
    @ContributesAndroidInjector(modules = [ScheduledActivityModule::class])
    abstract fun bindScheduledActivity(): ScheduledActivity

    @ActivityScope
    @ContributesAndroidInjector(modules = [])
    abstract fun bindSettingsActivity(): SettingsActivity

    @ActivityScope
    @ContributesAndroidInjector(modules = [])
    abstract fun bindBlockingActivity(): BlockingActivity


    @ActivityScope
    @ContributesAndroidInjector(modules = [])
    abstract fun bindSignalConversationsActivity(): SignalConversationsActivity


    @ActivityScope
    @ContributesAndroidInjector(modules = [])
    abstract fun bindSignalThreadActivity(): SignalThreadActivity

    @ActivityScope
    @ContributesAndroidInjector(modules = [])
    abstract fun bindSignalThreadInfoActivity(): SignalThreadInfoActivity

}
