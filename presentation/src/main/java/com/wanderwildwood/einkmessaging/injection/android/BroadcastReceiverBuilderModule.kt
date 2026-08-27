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
package com.wanderwildwood.einkmessaging.injection.android

import dagger.Module
import dagger.android.ContributesAndroidInjector
import com.wanderwildwood.einkmessaging.feature.widget.WidgetProvider
import com.wanderwildwood.einkmessaging.injection.scope.ActivityScope
import com.wanderwildwood.einkmessaging.receiver.BlockThreadReceiver
import com.wanderwildwood.einkmessaging.receiver.BootReceiver
import com.wanderwildwood.einkmessaging.receiver.DefaultSmsChangedReceiver
import com.wanderwildwood.einkmessaging.receiver.DeleteMessagesReceiver
import com.wanderwildwood.einkmessaging.receiver.MmsReceivedReceiver
import com.wanderwildwood.einkmessaging.receiver.MmsWapPushReceiver
import com.wanderwildwood.einkmessaging.receiver.NightModeReceiver
import com.wanderwildwood.einkmessaging.receiver.RemoteMessagingReceiver
import com.wanderwildwood.einkmessaging.receiver.SendScheduledMessageReceiver
import com.wanderwildwood.einkmessaging.receiver.MessageDeliveredReceiver
import com.wanderwildwood.einkmessaging.receiver.SmsProviderChangedReceiver
import com.wanderwildwood.einkmessaging.receiver.SmsReceivedReceiver
import com.wanderwildwood.einkmessaging.receiver.MessageMarkReceiver
import com.wanderwildwood.einkmessaging.receiver.MessageSentReceiver
import com.wanderwildwood.einkmessaging.receiver.ResendMessageReceiver
import com.wanderwildwood.einkmessaging.receiver.SendDelayedMessageReceiver
import com.wanderwildwood.einkmessaging.receiver.SpeakThreadsReceiver
import com.wanderwildwood.einkmessaging.receiver.StartActivityFromWidgetReceiver

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
    abstract fun bindDesktopSyncBootReceiver(): com.wanderwildwood.einkmessaging.feature.desktopsync.DesktopSyncBootReceiver

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