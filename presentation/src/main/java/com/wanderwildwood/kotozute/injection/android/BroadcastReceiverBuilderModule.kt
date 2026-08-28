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
import com.wanderwildwood.kotozute.feature.widget.WidgetProvider
import com.wanderwildwood.kotozute.injection.scope.ActivityScope
import com.wanderwildwood.kotozute.receiver.BlockThreadReceiver
import com.wanderwildwood.kotozute.receiver.BootReceiver
import com.wanderwildwood.kotozute.receiver.DefaultSmsChangedReceiver
import com.wanderwildwood.kotozute.receiver.DeleteMessagesReceiver
import com.wanderwildwood.kotozute.receiver.MmsReceivedReceiver
import com.wanderwildwood.kotozute.receiver.MmsWapPushReceiver
import com.wanderwildwood.kotozute.receiver.NightModeReceiver
import com.wanderwildwood.kotozute.receiver.RemoteMessagingReceiver
import com.wanderwildwood.kotozute.receiver.SendScheduledMessageReceiver
import com.wanderwildwood.kotozute.receiver.MessageDeliveredReceiver
import com.wanderwildwood.kotozute.receiver.SmsProviderChangedReceiver
import com.wanderwildwood.kotozute.receiver.SmsReceivedReceiver
import com.wanderwildwood.kotozute.receiver.MessageMarkReceiver
import com.wanderwildwood.kotozute.receiver.MessageSentReceiver
import com.wanderwildwood.kotozute.receiver.ResendMessageReceiver
import com.wanderwildwood.kotozute.receiver.SendDelayedMessageReceiver
import com.wanderwildwood.kotozute.receiver.SpeakThreadsReceiver
import com.wanderwildwood.kotozute.receiver.StartActivityFromWidgetReceiver

@Module
abstract class BroadcastReceiverBuilderModule {

    @ActivityScope
    @ContributesAndroidInjector
    abstract fun bindBlockThreadReceiver(): BlockThreadReceiver

    @ActivityScope
    @ContributesAndroidInjector
    abstract fun bindBootReceiver(): BootReceiver

    @ActivityScope
    @ContributesAndroidInjector
    abstract fun bindDesktopSyncBootReceiver(): com.wanderwildwood.kotozute.feature.desktopsync.DesktopSyncBootReceiver

    @ActivityScope
    @ContributesAndroidInjector
    abstract fun bindDefaultSmsChangedReceiver(): DefaultSmsChangedReceiver

    @ActivityScope
    @ContributesAndroidInjector
    abstract fun bindDeleteMessagesReceiver(): DeleteMessagesReceiver

    @ActivityScope
    @ContributesAndroidInjector
    abstract fun bindSpeakThreadsReceiver(): SpeakThreadsReceiver

    @ActivityScope
    @ContributesAndroidInjector
    abstract fun bindStartActivityFromWidgetReceiver(): StartActivityFromWidgetReceiver

    @ActivityScope
    @ContributesAndroidInjector
    abstract fun bindMmsReceivedReceiver(): MmsReceivedReceiver

    @ActivityScope
    @ContributesAndroidInjector
    abstract fun bindMmsWapPushReceiver(): MmsWapPushReceiver

    @ActivityScope
    @ContributesAndroidInjector
    abstract fun bindNightModeReceiver(): NightModeReceiver

    @ActivityScope
    @ContributesAndroidInjector
    abstract fun bindRemoteMessagingReceiver(): RemoteMessagingReceiver

    @ActivityScope
    @ContributesAndroidInjector
    abstract fun bindResendMessageReceiver(): ResendMessageReceiver

    @ActivityScope
    @ContributesAndroidInjector
    abstract fun bindSendScheduledMessageReceiver(): SendScheduledMessageReceiver

    @ActivityScope
    @ContributesAndroidInjector
    abstract fun bindSendDelayedMessageReceiver(): SendDelayedMessageReceiver

    @ActivityScope
    @ContributesAndroidInjector
    abstract fun bindMessageDeliveredReceiver(): MessageDeliveredReceiver

    @ActivityScope
    @ContributesAndroidInjector
    abstract fun bindSmsProviderChangedReceiver(): SmsProviderChangedReceiver

    @ActivityScope
    @ContributesAndroidInjector
    abstract fun bindSmsReceivedReceiver(): SmsReceivedReceiver

    @ActivityScope
    @ContributesAndroidInjector
    abstract fun bindMessageSentReceiver(): MessageSentReceiver

    @ActivityScope
    @ContributesAndroidInjector
    abstract fun bindMessageMarkReceiver(): MessageMarkReceiver

    @ActivityScope
    @ContributesAndroidInjector
    abstract fun bindWidgetProvider(): WidgetProvider

}