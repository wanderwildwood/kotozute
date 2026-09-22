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
package com.wanderwildwood.kotozute.feature.settings

import com.wanderwildwood.kotozute.repository.SyncRepository
import com.wanderwildwood.kotozute.util.Preferences

data class SettingsState(
    val black: Boolean = false,
    val autoColor: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val sendDelaySummary: String = "",
    val sendDelayId: Int = 0,
    val deliveryEnabled: Boolean = false,
    val readReceiptsEnabled: Boolean = false,
    val unreadAtTopEnabled: Boolean = false,
    val signature: String = "",
    val textSizeSummary: String = "",
    val textSizeId: Int = Preferences.TEXT_SIZE_NORMAL,
    val splitSmsEnabled: Boolean = false,
    val stripUnicodeEnabled: Boolean = false,
    val mobileOnly: Boolean = false,
    val autoDelete: Int = 0,
    val longAsMms: Boolean = false,
    val maxMmsSizeSummary: String = "100KB",
    val maxMmsSizeId: Int = 100,
    val messageLinkHandlingSummary: String = "Ask before opening",
    val messageLinkHandlingId: Int = 2,
    val disableScreenshotsEnabled: Boolean = false,
    val syncProgress: SyncRepository.SyncProgress = SyncRepository.SyncProgress.Idle,
    val desktopSyncSummary: String = "",
    val desktopSyncEnabled: Boolean = false,
    val desktopSyncTailscaleOnly: Boolean = true,
    val signalPaired: Boolean = false,
    val signalEnabled: Boolean = false,
    /** This phone is a device on the account. */
    val signalLinkedDirectly: Boolean = false,
    /**
     * This phone is the account's **primary**, not a linked device -- so it owns the profile.
     *
     * Separate from [signalLinkedDirectly], which is true for both. Only a primary may write
     * the account's own name.
     */
    val signalIsPrimary: Boolean = false,
    /** Desktop Sync serves over TLS. See [Preferences.desktopSyncTls]. */
    val desktopSyncTls: Boolean = false,
    val signalStatusSummary: String = "",
    val signalKeepConnected: Boolean = false,
    val signalWeave: Boolean = true,
    val signalReadReceipts: Boolean = false,
    /**
     * What the update row currently says, and what a tap on it would do.
     *
     * One field rather than a summary string and a handful of booleans: the row has exactly one
     * face at a time, and the face is the whole of what the presenter decided.
     */
    val update: UpdateRow = UpdateRow.Idle("")
)

/**
 * The faces of the update row, in the order a person meets them.
 *
 * [Available] and [Armed] carry the version twice over because the row names both: what it
 * would install, and what that replaces.
 */
sealed interface UpdateRow {

    /** Offering a check. Shows what is running. */
    data class Idle(val running: String) : UpdateRow

    /** A check or a download is in flight. */
    data class Busy(val message: Int) : UpdateRow

    /** A check came back with something newer. Offers the install, unarmed. */
    data class Available(val version: String, val running: String) : UpdateRow

    /** The install has been offered and tapped once. A second tap does it. */
    data class Armed(val version: String, val running: String) : UpdateRow

    /** Something to say and nothing to do about it. Shows [message] and goes back to [Idle]. */
    data class Reported(val message: Int, val running: String) : UpdateRow

    /** Android will not let this app install packages. Tapping opens the screen that changes that. */
    object NotPermitted : UpdateRow

}
