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
package com.wanderwildwood.kotozute.manager

import android.app.Notification
import androidx.core.app.NotificationCompat

interface NotificationManager {

    fun getForegroundNotificationForWorkersOnOlderAndroids(): Notification

    fun update(threadId: Long)

    fun notifyFailed(threadId: Long)

    fun createNotificationChannel(threadId: Long = 0L)

    fun buildNotificationChannelId(threadId: Long): String

    fun getNotificationForBackup(): NotificationCompat.Builder

    /**
     * Says that [version] has been published, and opens settings when tapped.
     *
     * Only ever called for a release the person has not been told about yet, and only for one
     * that is more than a patch ahead -- this app tags several times a day, and a notification
     * per tag is a notification nobody reads.
     */
    fun notifyUpdateAvailable(version: String)

    fun cancel(i: Int)

}
