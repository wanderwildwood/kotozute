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

import android.content.Context
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.Navigator
import com.wanderwildwood.kotozute.common.base.QkPresenter
import com.wanderwildwood.kotozute.common.util.extensions.makeToast
import com.wanderwildwood.kotozute.feature.desktopsync.DesktopSyncService
import com.wanderwildwood.kotozute.interactor.DeleteOldMessages
import com.wanderwildwood.kotozute.interactor.SyncMessages
import com.wanderwildwood.kotozute.repository.MessageRepository
import com.wanderwildwood.kotozute.repository.SyncRepository
import com.wanderwildwood.kotozute.service.AutoDeleteService
import com.wanderwildwood.kotozute.util.Preferences
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.util.concurrent.TimeUnit
import com.wanderwildwood.kotozute.repository.SignalRepository
import android.text.format.DateUtils
import javax.inject.Inject

class SettingsPresenter @Inject constructor(
    syncRepo: SyncRepository,
    private val context: Context,
    private val deleteOldMessages: DeleteOldMessages,
    private val messageRepo: MessageRepository,
    private val navigator: Navigator,
    private val prefs: Preferences,
    private val signalRepo: SignalRepository,
    private val syncMessages: SyncMessages
) : QkPresenter<SettingsView, SettingsState>(SettingsState()) {

    init {
        disposables += prefs.black.asObservable()
                .subscribe { black -> newState { copy(black = black) } }

        disposables += prefs.signalReadReceipts.asObservable()
                .subscribe { on -> newState { copy(signalReadReceipts = on) } }

        disposables += prefs.signalWeave.asObservable()
                .subscribe { on -> newState { copy(signalWeave = on) } }

        disposables += signalRepo.connectionState()
                .subscribe { conn ->
                    newState {
                        copy(
                            signalPaired = conn.configured,
                            signalEnabled = conn.enabled,
                            signalBridgeSummary = signalBridgeSummary(conn.configured),
                            signalStatusSummary = signalStatusSummary(conn)
                        )
                    }
                }

        disposables += prefs.notifications().asObservable()
                .subscribe { enabled -> newState { copy(notificationsEnabled = enabled) } }

        val delayedSendingLabels = context.resources.getStringArray(R.array.delayed_sending_labels)
        disposables += prefs.sendDelay.asObservable()
                .subscribe { id -> newState { copy(sendDelaySummary = delayedSendingLabels[id], sendDelayId = id) } }

        disposables += prefs.delivery.asObservable()
            .subscribe { enabled -> newState { copy(deliveryEnabled = enabled) } }

        disposables += prefs.readReceipts.asObservable()
            .subscribe { enabled -> newState { copy(readReceiptsEnabled = enabled) } }

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
                        R.id.categoryDisplay ->
                            view.showSection(R.id.sectionDisplay, R.string.settings_category_general)

                        R.id.categorySending ->
                            view.showSection(R.id.sectionSending, R.string.settings_category_sending)

                        R.id.categoryStorage ->
                            view.showSection(R.id.sectionStorage, R.string.settings_category_storage)

                        R.id.categoryDesktopSync ->
                            view.showSection(R.id.sectionDesktop, R.string.settings_category_desktop_sync)

                        R.id.categorySignal ->
                            view.showSection(R.id.sectionSignal, R.string.settings_category_signal)

                        R.id.archived -> navigator.showArchived()

                        R.id.scheduled -> navigator.showScheduled(null)

                        R.id.blocking -> navigator.showBlockedConversations()

                        R.id.backup -> navigator.showBackup()


                        R.id.notifications -> navigator.showNotificationSettings()

                        R.id.swipeActions -> view.showSwipeActions()

                        R.id.delayed -> view.showDelayDurationDialog()

                        R.id.delivery -> prefs.delivery.set(!prefs.delivery.get())

                        R.id.readReceipts -> prefs.readReceipts.set(!prefs.readReceipts.get())

                        R.id.desktopSync -> {
                            if (prefs.desktopSyncEnabled.get()) {
                                DesktopSyncService.stop(context)
                            } else {
                                DesktopSyncService.start(context)
                            }
                        }

                        R.id.desktopSyncLink -> view.showDesktopSyncLinkDialog(desktopSyncUrls())

                        R.id.desktopSyncTailscaleOnly ->
                            prefs.desktopSyncTailscaleOnly.set(!prefs.desktopSyncTailscaleOnly.get())

                        R.id.desktopSyncReset -> view.askDesktopSyncReset()

                        R.id.signalPair -> view.showSignalPairDialog()

                        R.id.signalOpen -> navigator.showSignalConversations()

                        // Enabling is only offered once a bridge is paired, so this
                        // switch cannot put Signal into a configured-but-broken state.
                        R.id.signalEnabled -> signalRepo.setEnabled(!prefs.signalEnabled.get())

                        R.id.signalReceipts ->
                            prefs.signalReadReceipts.set(!prefs.signalReadReceipts.get())

                        R.id.signalWeave -> prefs.signalWeave.set(!prefs.signalWeave.get())

                        R.id.signalUnpair -> view.askSignalUnpair()

                        R.id.unreadAtTop -> prefs.unreadAtTop.set(!prefs.unreadAtTop.get())

                        R.id.signature -> view.showSignatureDialog(prefs.signature.get())

                        R.id.textSize -> view.showTextSizePicker()



                        R.id.unicode -> prefs.unicode.set(!prefs.unicode.get())

                        R.id.mobileOnly -> prefs.mobileOnly.set(!prefs.mobileOnly.get())

                        R.id.autoDelete -> view.showAutoDeleteDialog(prefs.autoDelete.get())

                        R.id.longAsMms -> prefs.longAsMms.set(!prefs.longAsMms.get())

                        R.id.mmsSize -> view.showMmsSizePicker()

                        R.id.messageLinkHandling -> view.showMessageLinkHandlingDialogPicker()

                        R.id.disableScreenshots -> prefs.disableScreenshots.set(!prefs.disableScreenshots.get())

                        R.id.sync -> syncMessages.execute(Unit)

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

        view.signalPairPayload()
            .autoDisposable(view.scope())
            .subscribe { payload ->
                if (signalRepo.pair(payload)) {
                    signalRepo.setEnabled(true)
                } else {
                    view.showSignalPairFailed()
                }
            }

        view.signalUnpairConfirmed()
            .autoDisposable(view.scope())
            .subscribe { signalRepo.unpair() }
    }

    private fun signalBridgeSummary(paired: Boolean): String {
        if (!paired) return context.getString(R.string.settings_signal_pair_summary)
        val host = prefs.signalBridgeHost.get()
        val port = prefs.signalBridgePort.get()
        return context.getString(R.string.settings_signal_paired_summary, "$host:$port")
    }

    /**
     * Say what is actually true. Receiving degrades softly -- messages queue on Signal's
     * servers while the bridge is away -- but sending simply cannot happen, so the line
     * names read-only rather than implying something is on its way.
     */
    private fun signalStatusSummary(conn: SignalRepository.ConnectionState): String {
        val last = when (val t = conn.lastSyncedAt) {
            0L -> context.getString(R.string.settings_signal_status_never)
            else -> context.getString(
                R.string.settings_signal_status_last_synced,
                DateUtils.getRelativeTimeSpanString(t).toString()
            )
        }
        return when {
            !conn.bridgeReachable ->
                context.getString(R.string.settings_signal_status_no_bridge) + " · " + last
            !conn.signalConnected ->
                context.getString(R.string.settings_signal_status_no_signal) + " · " + last
            else -> context.getString(R.string.settings_signal_status_ok) + " · " + last
        }
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
        val tailscale = DesktopSyncService.findTailscaleAddress(context)
        if (prefs.desktopSyncTailscaleOnly.get() && tailscale == null) {
            return "On, but Tailscale isn't connected"
        }
        if (tailscale == null && DesktopSyncService.findLanAddress(context) == null) {
            return "On, but this phone has no network yet"
        }
        return "On"
    }

    /**
     * Every address the computer could open, labelled, or empty if nothing can reach this
     * phone right now. All of them rather than one: the relay binds every interface, and
     * which address the computer can see is not knowable from here. Under the tailnet
     * restriction only the tailnet address is offered, because the others answer 403.
     */
    private fun desktopSyncUrls(): List<Pair<String, String>> {
        val token = prefs.desktopSyncToken.get()
        return DesktopSyncService.reachableAddresses(context)
            .filter {
                !prefs.desktopSyncTailscaleOnly.get() ||
                    it.first == DesktopSyncService.LABEL_TAILSCALE
            }
            .map { (label, host) ->
                label to "http://$host:${DesktopSyncService.PORT}?token=$token"
            }
    }

}