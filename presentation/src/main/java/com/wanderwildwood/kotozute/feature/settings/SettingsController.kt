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

import android.animation.ObjectAnimator
import android.app.TimePickerDialog
import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.bluelinelabs.conductor.RouterTransaction
import com.google.android.material.snackbar.Snackbar
import com.jakewharton.rxbinding2.view.clicks
import com.jakewharton.rxbinding2.view.longClicks
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.MenuItem
import com.wanderwildwood.kotozute.common.QkChangeHandler
import com.wanderwildwood.kotozute.common.QkDialog
import com.wanderwildwood.kotozute.common.base.QkController
import com.wanderwildwood.kotozute.common.util.Colors
import com.wanderwildwood.kotozute.common.util.extensions.animateLayoutChanges
import com.wanderwildwood.kotozute.common.util.extensions.setBackgroundTint
import com.wanderwildwood.kotozute.common.util.extensions.setVisible
import com.wanderwildwood.kotozute.common.widget.PreferenceView
import com.wanderwildwood.kotozute.common.widget.TextInputDialog
import com.wanderwildwood.kotozute.feature.settings.about.AboutDialog
import com.wanderwildwood.kotozute.feature.settings.autodelete.AutoDeleteDialog
import com.wanderwildwood.kotozute.feature.settings.swipe.SwipeActionsController
import com.wanderwildwood.kotozute.injection.appComponent
import com.wanderwildwood.kotozute.repository.SyncRepository
import com.wanderwildwood.kotozute.util.Preferences
import io.reactivex.Observable
import android.view.ViewGroup
import androidx.core.view.isVisible
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.resume
import com.wanderwildwood.kotozute.databinding.SettingsControllerBinding

class SettingsController : QkController<SettingsView, SettingsState, SettingsPresenter>(), SettingsView {

    private val binding get() = SettingsControllerBinding.bind(containerView!!)

    @Inject lateinit var context: Context
    @Inject lateinit var colors: Colors
    @Inject lateinit var textSizeDialog: QkDialog
    @Inject lateinit var sendDelayDialog: QkDialog
    @Inject lateinit var mmsSizeDialog: QkDialog
    @Inject lateinit var messageLinkHandlingDialog: QkDialog

    @Inject override lateinit var presenter: SettingsPresenter

    private val signatureDialog: TextInputDialog by lazy {
        TextInputDialog(activity!!, context.getString(R.string.settings_signature_title), signatureSubject::onNext)
    }
    private val autoDeleteDialog: AutoDeleteDialog by lazy {
        AutoDeleteDialog(activity!!, autoDeleteSubject::onNext)
    }
    private val aboutDialog: AboutDialog by lazy {
        AboutDialog(activity!!) { aboutLongClickSubject.onNext(Unit) }
    }

    private val signatureSubject: Subject<String> = PublishSubject.create()
    private val autoDeleteSubject: Subject<Int> = PublishSubject.create()
    private val desktopSyncResetSubject: Subject<Unit> = PublishSubject.create()
    private val signalPairSubject: Subject<String> = PublishSubject.create()
    private val signalUnpairSubject: Subject<Unit> = PublishSubject.create()
    private val aboutLongClickSubject: Subject<Unit> = PublishSubject.create()


    init {
        appComponent.inject(this)
        retainViewMode = RetainViewMode.RETAIN_DETACH
        layoutRes = R.layout.settings_controller
        setHasOptionsMenu(true)

        colors.themeObservable()
                .autoDisposable(scope())
                .subscribe { activity?.recreate() }
    }

    override fun onViewCreated() {
        binding.preferences.postDelayed({ binding.preferences?.animateLayoutChanges = true }, 100)
        textSizeDialog.adapter.setData(R.array.text_sizes)
        sendDelayDialog.adapter.setData(R.array.delayed_sending_labels)
        mmsSizeDialog.adapter.setData(R.array.mms_sizes, R.array.mms_sizes_ids)
        messageLinkHandlingDialog.adapter.setData(R.array.messageLinkHandlings, R.array.messageLinkHandling_ids)

    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        // the view is retained across detach, so restore whichever section was open
        setTitle(openTitle)
        showBackButton(true)
    }

    /**
     * The rows now live inside per-section containers rather than directly under [preferences],
     * so this walks the tree instead of only the immediate children.
     */
    private fun collectPreferences(group: ViewGroup): List<PreferenceView> =
            (0 until group.childCount)
                    .map { index -> group.getChildAt(index) }
                    .flatMap { child ->
                        when (child) {
                            is PreferenceView -> listOf(child)
                            is ViewGroup -> collectPreferences(child)
                            else -> emptyList()
                        }
                    }

    override fun preferenceClicks(): Observable<PreferenceView> = collectPreferences(binding.preferences)
            .map { preference -> preference.clicks().map { preference } }
            .let { preferences -> Observable.merge(preferences) }

    /**
     * Sections are swapped in place rather than pushed as separate controllers: every row still
     * exists in one layout, so the presenter's render() keeps working untouched. It also avoids a
     * push animation, which this app deliberately does not want on e-ink.
     */
    private var openSection: Int = 0
    private var openTitle: Int = R.string.title_settings

    override fun showSection(container: Int, title: Int) {
        openSection = container
        openTitle = title
        sectionContainers().forEach { section -> section.isVisible = section.id == container }
        setTitle(title)
        setAboutVisible(themedActivity?.toolbar?.menu)
    }

    private fun sectionContainers() = listOf(
            binding.sectionRoot, binding.sectionDisplay,
            binding.sectionSending, binding.sectionStorage, binding.sectionDesktop,
            binding.sectionSignal)

    override fun handleBack(): Boolean {
        if (openSection != 0 && openSection != binding.sectionRoot.id) {
            showSection(binding.sectionRoot.id, R.string.title_settings)
            return true
        }
        return super.handleBack()
    }

    override fun aboutLongClicks(): Observable<*> = aboutLongClickSubject

    override fun desktopSyncResetConfirmed(): Observable<*> = desktopSyncResetSubject

    override fun signalPairPayload(): Observable<String> = signalPairSubject

    override fun signalUnpairConfirmed(): Observable<*> = signalUnpairSubject

    private companion object {
        /** How long an armed row stays armed. Birding's ConfirmingRow uses the same five seconds. */
        const val ARM_TIMEOUT_MS = 5000L
    }

    /** Whether the reset row is armed, and the callback that stands it back down. */
    private var resetArmed = false
    private val disarmRunnable = Runnable { disarmReset() }

    private var unpairArmed = false
    private val disarmUnpairRunnable = Runnable { disarmUnpair() }

    private fun disarmUnpair() {
        unpairArmed = false
        binding.signalUnpair.removeCallbacks(disarmUnpairRunnable)
        binding.signalUnpair.title = activity?.getString(R.string.settings_signal_unpair_title).orEmpty()
        binding.signalUnpair.summary = activity?.getString(R.string.settings_signal_unpair_summary)
    }

    private fun disarmReset() {
        resetArmed = false
        binding.desktopSyncReset.removeCallbacks(disarmRunnable)
        binding.desktopSyncReset.title = activity?.getString(R.string.settings_desktop_sync_reset_title).orEmpty()
        binding.desktopSyncReset.summary = activity?.getString(R.string.settings_desktop_sync_reset_summary)
    }





    override fun textSizeSelected(): Observable<Int> = textSizeDialog.adapter.menuItemClicks

    override fun sendDelaySelected(): Observable<Int> = sendDelayDialog.adapter.menuItemClicks

    override fun signatureChanged(): Observable<String> = signatureSubject

    override fun autoDeleteChanged(): Observable<Int> = autoDeleteSubject

    override fun mmsSizeSelected(): Observable<Int> = mmsSizeDialog.adapter.menuItemClicks

    override fun messageLinkHandlingSelected(): Observable<Int> = messageLinkHandlingDialog.adapter.menuItemClicks

    override fun render(state: SettingsState) {



        binding.delayed.summary = state.sendDelaySummary
        sendDelayDialog.adapter.selectedItem = state.sendDelayId

        binding.delivery.checkbox.isChecked = state.deliveryEnabled
        binding.readReceipts.checkbox.isChecked = state.readReceiptsEnabled
        binding.desktopSync.summary = state.desktopSyncSummary
        // Nothing to reset until there's a link to reset.
        binding.desktopSync.checkbox.isChecked = state.desktopSyncEnabled
        binding.desktopSyncLink.setVisible(state.desktopSyncEnabled)
        binding.desktopSyncTailscaleOnly.setVisible(state.desktopSyncEnabled)
        binding.desktopSyncTailscaleOnly.checkbox.isChecked = state.desktopSyncTailscaleOnly
        binding.desktopSyncReset.setVisible(state.desktopSyncEnabled)

        binding.signalPair.summary = state.signalBridgeSummary
        binding.signalEnabled.setVisible(state.signalPaired)
        binding.signalEnabled.checkbox.isChecked = state.signalEnabled
        binding.signalUnpair.setVisible(state.signalPaired)
        // The status line only means anything once Signal is actually switched on.
        binding.signalOpen.setVisible(state.signalPaired && state.signalEnabled)
        binding.signalReceipts.setVisible(state.signalPaired && state.signalEnabled)
        binding.signalReceipts.checkbox.isChecked = state.signalReadReceipts
        binding.signalStatus.setVisible(state.signalPaired && state.signalEnabled)
        binding.signalStatus.summary = state.signalStatusSummary

        binding.unreadAtTop.checkbox.isChecked = state.unreadAtTopEnabled

        binding.signature.summary = state.signature.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.settings_signature_summary)

        binding.textSize.summary = state.textSizeSummary
        textSizeDialog.adapter.selectedItem = state.textSizeId




        binding.unicode.checkbox.isChecked = state.stripUnicodeEnabled
        binding.mobileOnly.checkbox.isChecked = state.mobileOnly

        binding.autoDelete.summary = when (state.autoDelete) {
            0 -> context.getString(R.string.settings_auto_delete_never)
            else -> context.resources.getQuantityString(
                    R.plurals.settings_auto_delete_summary, state.autoDelete, state.autoDelete)
        }

        binding.longAsMms.checkbox.isChecked = state.longAsMms

        binding.mmsSize.summary = state.maxMmsSizeSummary
        mmsSizeDialog.adapter.selectedItem = state.maxMmsSizeId

        binding.messageLinkHandling.summary = state.messageLinkHandlingSummary
        messageLinkHandlingDialog.adapter.selectedItem = state.messageLinkHandlingId

        binding.disableScreenshots.checkbox.isChecked = state.disableScreenshotsEnabled

        // The Sync row says how far it has got, in the place a row shows its value. There was a
        // bar under it that slid towards the same number -- motion an e-ink panel pays for in
        // full redraws, to say what the count says exactly.
        binding.sync.summary = when (state.syncProgress) {
            is SyncRepository.SyncProgress.Idle -> activity?.getString(R.string.settings_sync_summary)
            is SyncRepository.SyncProgress.Running -> when {
                state.syncProgress.indeterminate || state.syncProgress.max <= 0 ->
                    activity?.getString(R.string.settings_syncing)
                else -> activity?.getString(
                    R.string.settings_syncing_count,
                    state.syncProgress.progress, state.syncProgress.max
                )
            }
        }
    }

    override fun showTextSizePicker() = textSizeDialog.show(activity!!)

    override fun showDelayDurationDialog() = sendDelayDialog.show(activity!!)

    override fun showSignatureDialog(signature: String) = signatureDialog.setText(signature).show()

    override fun showAutoDeleteDialog(days: Int) = autoDeleteDialog.setExpiry(days).show()

    override suspend fun showAutoDeleteWarningDialog(messages: Int): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            AlertDialog.Builder(activity!!)
                    .setTitle(R.string.settings_auto_delete_warning)
                    .setMessage(context.resources.getString(R.string.settings_auto_delete_warning_message, messages))
                    .setOnCancelListener { cont.resume(false) }
                    .setNegativeButton(R.string.button_cancel) { _, _ -> cont.resume(false) }
                    .setPositiveButton(R.string.button_yes) { _, _ -> cont.resume(true) }
                    .show()
        }
    }

    override fun showMmsSizePicker() = mmsSizeDialog.show(activity!!)

    override fun showMessageLinkHandlingDialogPicker() = messageLinkHandlingDialog.show(activity!!)

    override fun showDesktopSyncLinkDialog(url: String?) {
        val message = if (url == null) {
            activity!!.getString(R.string.settings_desktop_sync_link_none)
        } else {
            url + "\n\n" + activity!!.getString(R.string.settings_desktop_sync_link_bookmark)
        }
        AlertDialog.Builder(activity!!)
                .setTitle(R.string.settings_desktop_sync_link_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
    }

    /**
     * The row asks, not a dialog. One tap arms it and it says what a second tap will do; the
     * second tap does it. A dialog is two full-panel repaints to ask one question, and on
     * e-ink that is the expensive way to ask.
     *
     * It disarms itself after a few seconds, so a stray tap does not leave a live trigger
     * sitting there for whoever picks the phone up next.
     */
    override fun askDesktopSyncReset() {
        if (resetArmed) {
            disarmReset()
            desktopSyncResetSubject.onNext(Unit)
            Toast.makeText(activity, R.string.settings_desktop_sync_reset_done, Toast.LENGTH_SHORT).show()
            return
        }
        resetArmed = true
        binding.desktopSyncReset.title = activity?.getString(R.string.settings_desktop_sync_reset_armed).orEmpty()
        binding.desktopSyncReset.summary = activity?.getString(R.string.settings_desktop_sync_reset_armed_summary)
        binding.desktopSyncReset.postDelayed(disarmRunnable, ARM_TIMEOUT_MS)
    }

    /**
     * Pairing is paste-only rather than typed: the payload carries a 43-character token
     * and a 64-character fingerprint, and neither is something to key in on a phone.
     * Run `kotozute-bridge --pairing` on the bridge host to print it.
     */
    override fun showSignalPairDialog() {
        val input = EditText(activity!!).apply {
            setHint(R.string.settings_signal_pair_dialog_hint)
            setSingleLine(false)
            maxLines = 4
        }
        AlertDialog.Builder(activity!!)
                .setTitle(R.string.settings_signal_pair_dialog_title)
                .setView(input)
                .setNegativeButton(R.string.button_cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    signalPairSubject.onNext(input.text.toString())
                }
                .show()
    }

    override fun showSignalPairFailed() {
        Toast.makeText(activity, R.string.settings_signal_pair_failed, Toast.LENGTH_SHORT).show()
    }

    /** Arm-and-confirm, for the same reason the reset row is: it destroys messages. */
    override fun askSignalUnpair() {
        if (unpairArmed) {
            disarmUnpair()
            signalUnpairSubject.onNext(Unit)
            return
        }
        unpairArmed = true
        binding.signalUnpair.title = activity?.getString(R.string.settings_signal_unpair_armed).orEmpty()
        binding.signalUnpair.summary = activity?.getString(R.string.settings_signal_unpair_armed_summary)
        binding.signalUnpair.postDelayed(disarmUnpairRunnable, ARM_TIMEOUT_MS)
    }

    override fun showSwipeActions() {
        router.pushController(RouterTransaction.with(SwipeActionsController())
                .pushChangeHandler(QkChangeHandler())
                .popChangeHandler(QkChangeHandler()))
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.settings, menu)
        setAboutVisible(menu)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean = when (item.itemId) {
        R.id.about -> {
            aboutDialog.show()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /**
     * About describes the app, not the section you happen to be standing in, so it is offered on
     * the settings list itself and withdrawn once a section is open.
     */
    private fun setAboutVisible(menu: Menu?) {
        menu?.findItem(R.id.about)?.isVisible =
                openSection == 0 || openSection == binding.sectionRoot.id
    }

}