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
package com.message.ink.feature.settings

import android.content.Context
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import com.message.ink.R
import com.message.ink.common.Navigator
import com.message.ink.common.base.QkPresenter
import com.message.ink.common.util.extensions.makeToast
import com.message.ink.feature.desktopsync.DesktopSyncService
import com.message.ink.interactor.DeleteOldMessages
import com.message.ink.interactor.SyncMessages
import com.message.ink.repository.MessageRepository
import com.message.ink.repository.SyncRepository
import com.message.ink.service.AutoDeleteService
import com.message.ink.util.Preferences
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class SettingsPresenter @Inject constructor(
    syncRepo: SyncRepository,
    private val context: Context,
    private val deleteOldMessages: DeleteOldMessages,
    private val messageRepo: MessageRepository,
    private val navigator: Navigator,
    private val prefs: Preferences,
    private val syncMessages: SyncMessages
) : QkPresenter<SettingsView, SettingsState>(SettingsState()) {

    init {
        disposables += prefs.black.asObservable()
                .subscribe { black -> newState { copy(black = black) } }

        disposables += prefs.notifications().asObservable()
                .subscribe { enabled -> newState { copy(notificationsEnabled = enabled) } }

        val delayedSendingLabels = context.resources.getStringArray(R.array.delayed_sending_labels)
        disposables += prefs.sendDelay.asObservable()
                .subscribe { id -> newState { copy(sendDelaySummary = delayedSendingLabels[id], sendDelayId = id) } }

        disposables += prefs.delivery.asObservable()
            .subscribe { enabled -> newState { copy(deliveryEnabled = enabled) } }

        disposables += prefs.desktopSyncEnabled.asObservable()
            .subscribe { enabled ->
                newState {
                    copy(desktopSyncSummary = desktopSyncSummary(enabled), desktopSyncEnabled = enabled)
                }
            }

        // The URL embeds the token, so a new token means a new summary to display.
        disposables += prefs.desktopSyncToken.asObservable()
            .subscribe {
                newState { copy(desktopSyncSummary = desktopSyncSummary(prefs.desktopSyncEnabled.get())) }
            }

        disposables += prefs.desktopSyncTailscaleOnly.asObservable()
            .subscribe { enabled ->
                newState {
                    copy(
                        desktopSyncTailscaleOnly = enabled,
                        desktopSyncSummary = desktopSyncSummary(prefs.desktopSyncEnabled.get()),
                    )
                }
            }

        disposables += prefs.unreadAtTop.asObservable()
            .subscribe { enabled -> newState { copy(unreadAtTopEnabled = enabled) } }

        disposables += prefs.signature.asObservable()
                .subscribe { signature -> newState { copy(signature = signature) } }

        val textSizeLabels = context.resources.getStringArray(R.array.text_sizes)
        disposables += prefs.textSize.asObservable()
                .subscribe { textSize ->
                    newState { copy(textSizeSummary = textSizeLabels[textSize], textSizeId = textSize) }
                }

        disposables += prefs.autoColor.asObservable()
                .subscribe { autoColor -> newState { copy(autoColor = autoColor) } }

        disposables += prefs.unicode.asObservable()
                .subscribe { enabled -> newState { copy(stripUnicodeEnabled = enabled) } }

        disposables += prefs.mobileOnly.asObservable()
                .subscribe { enabled -> newState { copy(mobileOnly = enabled) } }

        disposables += prefs.autoDelete.asObservable()
                .subscribe { autoDelete -> newState { copy(autoDelete = autoDelete) } }

        disposables += prefs.longAsMms.asObservable()
                .subscribe { enabled -> newState { copy(longAsMms = enabled) } }

        val mmsSizeLabels = context.resources.getStringArray(R.array.mms_sizes)
        val mmsSizeIds = context.resources.getIntArray(R.array.mms_sizes_ids)
        disposables += prefs.mmsSize.asObservable()
                .subscribe { maxMmsSize ->
                    val index = mmsSizeIds.indexOf(maxMmsSize)
                    newState { copy(maxMmsSizeSummary = mmsSizeLabels[index], maxMmsSizeId = maxMmsSize) }
                }

        val messageLinkHandlingLabels = context.resources.getStringArray(R.array.messageLinkHandlings)
        val messageLinkHandlingIds = context.resources.getIntArray(R.array.messageLinkHandling_ids)
        disposables += prefs.messageLinkHandling.asObservable()
            .subscribe { messageLinkHandlingId ->
                val index = messageLinkHandlingIds.indexOf(messageLinkHandlingId)
                newState {
                    copy(
                        messageLinkHandlingSummary = messageLinkHandlingLabels[index],
                        messageLinkHandlingId = messageLinkHandlingId
                    )
                }
            }
        disposables += prefs.disableScreenshots.asObservable()
            .subscribe { enabled -> newState { copy(disableScreenshotsEnabled = enabled) } }

        disposables += syncRepo.syncProgress
                .sample(16, TimeUnit.MILLISECONDS)
                .distinctUntilChanged()
                .subscribe { syncProgress -> newState { copy(syncProgress = syncProgress) } }

        disposables += syncMessages
    }

    override fun bindIntents(view: SettingsView) {
        super.bindIntents(view)

        view.preferenceClicks()
                .autoDisposable(view.scope())
                .subscribe {
                    Timber.v("Preference click: ${context.resources.getResourceName(it.id)}")

                    when (it.id) {
                        R.id.archived -> navigator.showArchived()

                        R.id.scheduled -> navigator.showScheduled(null)

                        R.id.blocking -> navigator.showBlockedConversations()

                        R.id.backup -> navigator.showBackup()


                        R.id.notifications -> navigator.showNotificationSettings()

                        R.id.swipeActions -> view.showSwipeActions()

                        R.id.delayed -> view.showDelayDurationDialog()

                        R.id.delivery -> prefs.delivery.set(!prefs.delivery.get())

                        R.id.desktopSync -> {
                            if (prefs.desktopSyncEnabled.get()) {
                                DesktopSyncService.stop(context)
                            } else {
                                DesktopSyncService.start(context)
                            }
                        }

                        R.id.desktopSyncLink -> view.showDesktopSyncLinkDialog(desktopSyncUrl())

                        R.id.desktopSyncTailscaleOnly ->
                            prefs.desktopSyncTailscaleOnly.set(!prefs.desktopSyncTailscaleOnly.get())

                        R.id.desktopSyncReset -> view.showDesktopSyncResetDialog()

                        R.id.unreadAtTop -> prefs.unreadAtTop.set(!prefs.unreadAtTop.get())

                        R.id.signature -> view.showSignatureDialog(prefs.signature.get())

                        R.id.textSize -> view.showTextSizePicker()



                        R.id.unicode -> prefs.unicode.set(!prefs.unicode.get())

                        R.id.mobileOnly -> prefs.mobileOnly.set(!prefs.mobileOnly.get())

                        R.id.autoDelete -> view.showAutoDeleteDialog(prefs.autoDelete.get())

                        R.id.longAsMms -> prefs.longAsMms.set(!prefs.longAsMms.get())

                        R.id.mmsSize -> view.showMmsSizePicker()

                        R.id.messsageLinkHandling -> view.showMessageLinkHandlingDialogPicker()

                        R.id.disableScreenshots -> prefs.disableScreenshots.set(!prefs.disableScreenshots.get())

                        R.id.sync -> syncMessages.execute(Unit)

                        R.id.about -> view.showAbout()
                    }
                }

        view.aboutLongClicks()
                .map { !prefs.logging.get() }
                .doOnNext { enabled -> prefs.logging.set(enabled) }
                .autoDisposable(view.scope())
                .subscribe { enabled ->
                    context.makeToast(when (enabled) {
                        true -> R.string.settings_logging_enabled
                        false -> R.string.settings_logging_disabled
                    })
                }

        view.textSizeSelected()
                .autoDisposable(view.scope())
                .subscribe(prefs.textSize::set)

        view.sendDelaySelected()
                .autoDisposable(view.scope())
                .subscribe(prefs.sendDelay::set)

        view.signatureChanged()
                .doOnNext(prefs.signature::set)
                .autoDisposable(view.scope())
                .subscribe()

        view.autoDeleteChanged()
                .observeOn(Schedulers.io())
                .filter { maxAge ->
                    if (maxAge == 0) {
                        return@filter true
                    }

                    val counts = messageRepo.getOldMessageCounts(maxAge)
                    if (counts.values.sum() == 0) {
                        return@filter true
                    }

                    runBlocking { view.showAutoDeleteWarningDialog(counts.values.sum()) }
                }
                .doOnNext { maxAge ->
                    when (maxAge == 0) {
                        true -> AutoDeleteService.cancelJob(context)
                        false -> {
                            AutoDeleteService.scheduleJob(context)
                            deleteOldMessages.execute(Unit)
                        }
                    }
                }
                .doOnNext(prefs.autoDelete::set)
                .autoDisposable(view.scope())
                .subscribe()

        view.mmsSizeSelected()
                .autoDisposable(view.scope())
                .subscribe(prefs.mmsSize::set)

        view.messageLinkHandlingSelected()
            .autoDisposable(view.scope())
            .subscribe(prefs.messageLinkHandling::set)

        view.desktopSyncResetConfirmed()
            .autoDisposable(view.scope())
            .subscribe { DesktopSyncService.resetToken(context) }
    }

    /**
     * One line, not a paragraph. The address moved into its own row + dialog: it only
     * matters while you're setting the dashboard up, and printing a URL with a secret
     * token in it on every visit to Settings is noise the rest of the time.
     */
    private fun desktopSyncSummary(enabled: Boolean): String {
        if (!enabled) {
            return context.getString(R.string.settings_desktop_sync_summary_off)
        }
        // "enabled" is the persisted intent; isRunning is whether a server is really
        // bound. They differ briefly during auto-restore, so report honestly.
        if (!DesktopSyncService.isRunning) {
            return "Starting…"
        }
        val tailscale = DesktopSyncService.findTailscaleAddress()
        if (prefs.desktopSyncTailscaleOnly.get() && tailscale == null) {
            return "On, but Tailscale isn't connected"
        }
        if (tailscale == null && DesktopSyncService.findLanAddress() == null) {
            return "On, but this phone has no network yet"
        }
        return "On"
    }

    /**
     * The address to open on the computer, or null if nothing can reach us right now.
     * Only offers the Wi-Fi address when the tailnet restriction is off, since otherwise
     * that address answers 403.
     */
    private fun desktopSyncUrl(): String? {
        val host = DesktopSyncService.findTailscaleAddress()
            ?: DesktopSyncService.findLanAddress().takeUnless { prefs.desktopSyncTailscaleOnly.get() }
            ?: return null
        return "http://$host:${DesktopSyncService.PORT}?token=${prefs.desktopSyncToken.get()}"
    }

}