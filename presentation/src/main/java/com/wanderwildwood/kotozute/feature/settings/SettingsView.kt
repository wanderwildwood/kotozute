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

import com.wanderwildwood.kotozute.common.base.QkViewContract
import com.wanderwildwood.kotozute.common.widget.PreferenceView
import io.reactivex.Observable

interface SettingsView : QkViewContract<SettingsState> {
    fun preferenceClicks(): Observable<PreferenceView>
    fun aboutLongClicks(): Observable<*>
    fun textSizeSelected(): Observable<Int>
    fun sendDelaySelected(): Observable<Int>
    fun signatureChanged(): Observable<String>
    fun autoDeleteChanged(): Observable<Int>
    fun mmsSizeSelected(): Observable<Int>
    fun messageLinkHandlingSelected(): Observable<Int>
    fun desktopSyncResetConfirmed(): Observable<*>
    fun signalPairPayload(): Observable<String>
    fun signalUnpairConfirmed(): Observable<*>

    /** The folder the reader picked out of the document picker, as a tree uri. */
    fun signalExportFolderChosen(): Observable<String>

    fun showTextSizePicker()
    fun showDelayDurationDialog()
    fun showSignatureDialog(signature: String)
    fun showAutoDeleteDialog(days: Int)
    suspend fun showAutoDeleteWarningDialog(messages: Int): Boolean
    fun showMmsSizePicker()
    fun showMessageLinkHandlingDialogPicker()
    fun showDesktopSyncLinkDialog(urls: List<Pair<String, String>>)
    fun askDesktopSyncReset()
    fun showSignalAccountDialog(account: com.wanderwildwood.kotozute.repository.SignalAccount?)
    fun showSignalHistoryDialog()
    fun chooseSignalExportFolder()

    /** How far an import has got, on the row itself: it is the only thing that is happening. */
    fun showSignalImportProgress(messages: Int)
    fun showSignalImportResult(stats: com.wanderwildwood.kotozute.repository.SignalRepository.ImportStats?)
    fun confirmStopUsingBridge()

    fun stopUsingBridgeConfirmed(): Observable<Unit>

    fun showSignalLink()

    fun showSignalRegister()

    /** Reveals the bridge row, which is not offered until someone asks for it. */
    fun showBridgeOption()

    fun showSignalPairDialog()
    fun showSignalPairFailed()
    fun askSignalUnpair()
    fun showSection(container: Int, title: Int)
    fun showSwipeActions()
}
