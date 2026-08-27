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
package com.wanderwildwood.einkmessaging.injection

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
import com.wanderwildwood.einkmessaging.blocking.BlockingClient
import com.wanderwildwood.einkmessaging.blocking.BlockingManager
import com.wanderwildwood.einkmessaging.common.ViewModelFactory
import com.wanderwildwood.einkmessaging.common.util.NotificationManagerImpl
import com.wanderwildwood.einkmessaging.common.util.ShortcutManagerImpl
import com.wanderwildwood.einkmessaging.feature.conversationinfo.injection.ConversationInfoComponent
import com.wanderwildwood.einkmessaging.listener.ContactAddedListener
import com.wanderwildwood.einkmessaging.listener.ContactAddedListenerImpl
import com.wanderwildwood.einkmessaging.manager.ActiveConversationManager
import com.wanderwildwood.einkmessaging.manager.ActiveConversationManagerImpl
import com.wanderwildwood.einkmessaging.manager.AlarmManager
import com.wanderwildwood.einkmessaging.manager.AlarmManagerImpl
import com.wanderwildwood.einkmessaging.manager.KeyManager
import com.wanderwildwood.einkmessaging.manager.KeyManagerImpl
import com.wanderwildwood.einkmessaging.manager.NotificationManager
import com.wanderwildwood.einkmessaging.manager.PermissionManager
import com.wanderwildwood.einkmessaging.manager.PermissionManagerImpl
import com.wanderwildwood.einkmessaging.manager.RatingManager
import com.wanderwildwood.einkmessaging.manager.ReferralManager
import com.wanderwildwood.einkmessaging.manager.ReferralManagerImpl
import com.wanderwildwood.einkmessaging.manager.ShortcutManager
import com.wanderwildwood.einkmessaging.manager.WidgetManager
import com.wanderwildwood.einkmessaging.manager.WidgetManagerImpl
import com.wanderwildwood.einkmessaging.mapper.CursorToContact
import com.wanderwildwood.einkmessaging.mapper.CursorToContactGroup
import com.wanderwildwood.einkmessaging.mapper.CursorToContactGroupImpl
import com.wanderwildwood.einkmessaging.mapper.CursorToContactGroupMember
import com.wanderwildwood.einkmessaging.mapper.CursorToContactGroupMemberImpl
import com.wanderwildwood.einkmessaging.mapper.CursorToContactImpl
import com.wanderwildwood.einkmessaging.mapper.CursorToConversation
import com.wanderwildwood.einkmessaging.mapper.CursorToConversationImpl
import com.wanderwildwood.einkmessaging.mapper.CursorToMessage
import com.wanderwildwood.einkmessaging.mapper.CursorToMessageImpl
import com.wanderwildwood.einkmessaging.mapper.CursorToPart
import com.wanderwildwood.einkmessaging.mapper.CursorToPartImpl
import com.wanderwildwood.einkmessaging.mapper.CursorToRecipient
import com.wanderwildwood.einkmessaging.mapper.CursorToRecipientImpl
import com.wanderwildwood.einkmessaging.mapper.RatingManagerImpl
import com.wanderwildwood.einkmessaging.repository.BackupRepository
import com.wanderwildwood.einkmessaging.repository.BackupRepositoryImpl
import com.wanderwildwood.einkmessaging.repository.BlockingRepository
import com.wanderwildwood.einkmessaging.repository.BlockingRepositoryImpl
import com.wanderwildwood.einkmessaging.repository.ContactRepository
import com.wanderwildwood.einkmessaging.repository.ContactRepositoryImpl
import com.wanderwildwood.einkmessaging.repository.ConversationRepository
import com.wanderwildwood.einkmessaging.repository.ConversationRepositoryImpl
import com.wanderwildwood.einkmessaging.repository.EmojiReactionRepository
import com.wanderwildwood.einkmessaging.repository.EmojiReactionRepositoryImpl
import com.wanderwildwood.einkmessaging.repository.MessageContentFilterRepository
import com.wanderwildwood.einkmessaging.repository.MessageContentFilterRepositoryImpl
import com.wanderwildwood.einkmessaging.repository.MessageRepository
import com.wanderwildwood.einkmessaging.repository.MessageRepositoryImpl
import com.wanderwildwood.einkmessaging.repository.ScheduledMessageRepository
import com.wanderwildwood.einkmessaging.repository.ScheduledMessageRepositoryImpl
import com.wanderwildwood.einkmessaging.repository.SyncRepository
import com.wanderwildwood.einkmessaging.repository.SyncRepositoryImpl
import com.wanderwildwood.einkmessaging.worker.InjectionWorkerFactory
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
    fun provideRatingManager(manager: RatingManagerImpl): RatingManager = manager

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
    fun provideScheduledMessagesRepository(repository: ScheduledMessageRepositoryImpl): ScheduledMessageRepository = repository

    @Provides
    fun provideSyncRepository(repository: SyncRepositoryImpl): SyncRepository = repository

    @Provides
    fun provideEmojiReactionRepository(repository: EmojiReactionRepositoryImpl): EmojiReactionRepository = repository

    // worker factory
    @Provides
    fun provideWorkerFactory(workerFactory: InjectionWorkerFactory): WorkerFactory = workerFactory
}