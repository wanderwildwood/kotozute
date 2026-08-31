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
package com.wanderwildwood.kotozute.injection

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import androidx.lifecycle.ViewModelProvider
import androidx.work.WorkerFactory
import com.f2prateek.rx.preferences2.RxSharedPreferences
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import com.wanderwildwood.kotozute.blocking.BlockingClient
import com.wanderwildwood.kotozute.blocking.BlockingManager
import com.wanderwildwood.kotozute.common.ViewModelFactory
import com.wanderwildwood.kotozute.common.util.NotificationManagerImpl
import com.wanderwildwood.kotozute.common.util.ShortcutManagerImpl
import com.wanderwildwood.kotozute.feature.conversationinfo.injection.ConversationInfoComponent
import com.wanderwildwood.kotozute.listener.ContactAddedListener
import com.wanderwildwood.kotozute.listener.ContactAddedListenerImpl
import com.wanderwildwood.kotozute.manager.ActiveConversationManager
import com.wanderwildwood.kotozute.manager.ActiveConversationManagerImpl
import com.wanderwildwood.kotozute.manager.AlarmManager
import com.wanderwildwood.kotozute.manager.AlarmManagerImpl
import com.wanderwildwood.kotozute.manager.KeyManager
import com.wanderwildwood.kotozute.manager.KeyManagerImpl
import com.wanderwildwood.kotozute.manager.NotificationManager
import com.wanderwildwood.kotozute.manager.PermissionManager
import com.wanderwildwood.kotozute.manager.PermissionManagerImpl
import com.wanderwildwood.kotozute.manager.ReferralManager
import com.wanderwildwood.kotozute.manager.ReferralManagerImpl
import com.wanderwildwood.kotozute.manager.ShortcutManager
import com.wanderwildwood.kotozute.manager.WidgetManager
import com.wanderwildwood.kotozute.manager.WidgetManagerImpl
import com.wanderwildwood.kotozute.mapper.CursorToContact
import com.wanderwildwood.kotozute.mapper.CursorToContactGroup
import com.wanderwildwood.kotozute.mapper.CursorToContactGroupImpl
import com.wanderwildwood.kotozute.mapper.CursorToContactGroupMember
import com.wanderwildwood.kotozute.mapper.CursorToContactGroupMemberImpl
import com.wanderwildwood.kotozute.mapper.CursorToContactImpl
import com.wanderwildwood.kotozute.mapper.CursorToConversation
import com.wanderwildwood.kotozute.mapper.CursorToConversationImpl
import com.wanderwildwood.kotozute.mapper.CursorToMessage
import com.wanderwildwood.kotozute.mapper.CursorToMessageImpl
import com.wanderwildwood.kotozute.mapper.CursorToPart
import com.wanderwildwood.kotozute.mapper.CursorToPartImpl
import com.wanderwildwood.kotozute.mapper.CursorToRecipient
import com.wanderwildwood.kotozute.mapper.CursorToRecipientImpl
import com.wanderwildwood.kotozute.repository.BackupRepository
import com.wanderwildwood.kotozute.repository.BackupRepositoryImpl
import com.wanderwildwood.kotozute.repository.BlockingRepository
import com.wanderwildwood.kotozute.repository.BlockingRepositoryImpl
import com.wanderwildwood.kotozute.repository.ContactRepository
import com.wanderwildwood.kotozute.repository.ContactRepositoryImpl
import com.wanderwildwood.kotozute.repository.ConversationRepository
import com.wanderwildwood.kotozute.repository.ConversationRepositoryImpl
import com.wanderwildwood.kotozute.repository.EmojiReactionRepository
import com.wanderwildwood.kotozute.repository.EmojiReactionRepositoryImpl
import com.wanderwildwood.kotozute.repository.MessageContentFilterRepository
import com.wanderwildwood.kotozute.repository.MessageContentFilterRepositoryImpl
import com.wanderwildwood.kotozute.repository.MessageRepository
import com.wanderwildwood.kotozute.repository.MessageRepositoryImpl
import com.wanderwildwood.kotozute.repository.ScheduledMessageRepository
import com.wanderwildwood.kotozute.repository.ScheduledMessageRepositoryImpl
import com.wanderwildwood.kotozute.repository.SignalRepository
import com.wanderwildwood.kotozute.repository.SignalRepositoryImpl
import com.wanderwildwood.kotozute.repository.SyncRepository
import com.wanderwildwood.kotozute.repository.SyncRepositoryImpl
import com.wanderwildwood.kotozute.worker.InjectionWorkerFactory
import javax.inject.Singleton

@Module(subcomponents = [
    ConversationInfoComponent::class])
class AppModule(private var application: Application) {

    @Provides
    @Singleton
    fun provideContext(): Context = application

    @Provides
    fun provideContentResolver(context: Context): ContentResolver = context.contentResolver

    @Provides
    @Singleton
    fun provideSharedPreferences(context: Context): SharedPreferences {
        return PreferenceManager.getDefaultSharedPreferences(context)
    }

    @Provides
    @Singleton
    fun provideRxPreferences(preferences: SharedPreferences): RxSharedPreferences {
        return RxSharedPreferences.create(preferences)
    }

    @Provides
    @Singleton
    fun provideMoshi(): Moshi {
        return Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()
    }

    @Provides
    fun provideViewModelFactory(factory: ViewModelFactory): ViewModelProvider.Factory = factory

    // Listener

    @Provides
    fun provideContactAddedListener(listener: ContactAddedListenerImpl): ContactAddedListener = listener

    // Manager

    @Provides
    fun provideActiveConversationManager(manager: ActiveConversationManagerImpl): ActiveConversationManager = manager

    @Provides
    fun provideAlarmManager(manager: AlarmManagerImpl): AlarmManager = manager

    @Provides
    fun blockingClient(manager: BlockingManager): BlockingClient = manager

    @Provides
    fun provideKeyManager(manager: KeyManagerImpl): KeyManager = manager

    @Provides
    fun provideNotificationsManager(manager: NotificationManagerImpl): NotificationManager = manager

    @Provides
    fun providePermissionsManager(manager: PermissionManagerImpl): PermissionManager = manager

    @Provides
    fun provideShortcutManager(manager: ShortcutManagerImpl): ShortcutManager = manager

    @Provides
    fun provideReferralManager(manager: ReferralManagerImpl): ReferralManager = manager

    @Provides
    fun provideWidgetManager(manager: WidgetManagerImpl): WidgetManager = manager

    // Mapper

    @Provides
    fun provideCursorToContact(mapper: CursorToContactImpl): CursorToContact = mapper

    @Provides
    fun provideCursorToContactGroup(mapper: CursorToContactGroupImpl): CursorToContactGroup = mapper

    @Provides
    fun provideCursorToContactGroupMember(mapper: CursorToContactGroupMemberImpl): CursorToContactGroupMember = mapper

    @Provides
    fun provideCursorToConversation(mapper: CursorToConversationImpl): CursorToConversation = mapper

    @Provides
    fun provideCursorToMessage(mapper: CursorToMessageImpl): CursorToMessage = mapper

    @Provides
    fun provideCursorToPart(mapper: CursorToPartImpl): CursorToPart = mapper

    @Provides
    fun provideCursorToRecipient(mapper: CursorToRecipientImpl): CursorToRecipient = mapper

    // Repository

    @Provides
    fun provideBackupRepository(repository: BackupRepositoryImpl): BackupRepository = repository

    @Provides
    fun provideBlockingRepository(repository: BlockingRepositoryImpl): BlockingRepository = repository

    @Provides
    fun provideMessageContentFilterRepository(repository: MessageContentFilterRepositoryImpl): MessageContentFilterRepository = repository

    @Provides
    fun provideContactRepository(repository: ContactRepositoryImpl): ContactRepository = repository

    @Provides
    fun provideConversationRepository(repository: ConversationRepositoryImpl): ConversationRepository = repository

    @Provides
    fun provideMessageRepository(repository: MessageRepositoryImpl): MessageRepository = repository


    @Provides
    fun provideSignalRepository(repository: SignalRepositoryImpl): SignalRepository = repository

    @Provides
    fun provideScheduledMessagesRepository(repository: ScheduledMessageRepositoryImpl): ScheduledMessageRepository = repository

    @Provides
    fun provideSyncRepository(repository: SyncRepositoryImpl): SyncRepository = repository

    @Provides
    fun provideEmojiReactionRepository(repository: EmojiReactionRepositoryImpl): EmojiReactionRepository = repository

    // worker factory
    @Provides
    fun provideWorkerFactory(workerFactory: InjectionWorkerFactory): WorkerFactory = workerFactory
}