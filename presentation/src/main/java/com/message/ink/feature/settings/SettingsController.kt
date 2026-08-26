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

import android.animation.ObjectAnimator
import android.app.TimePickerDialog
import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.bluelinelabs.conductor.RouterTransaction
import com.google.android.material.snackbar.Snackbar
import com.jakewharton.rxbinding2.view.clicks
import com.jakewharton.rxbinding2.view.longClicks
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import com.message.ink.BuildConfig
import com.message.ink.R
import com.message.ink.common.MenuItem
import com.message.ink.common.QkChangeHandler
import com.message.ink.common.QkDialog
import com.message.ink.common.base.QkController
import com.message.ink.common.util.Colors
import com.message.ink.common.util.extensions.animateLayoutChanges
import com.message.ink.common.util.extensions.setBackgroundTint
import com.message.ink.common.util.extensions.setVisible
import com.message.ink.common.widget.PreferenceView
import com.message.ink.common.widget.TextInputDialog
import com.message.ink.feature.settings.about.AboutController
import com.message.ink.feature.settings.autodelete.AutoDeleteDialog
import com.message.ink.feature.settings.swipe.SwipeActionsController
import com.message.ink.injection.appComponent
import com.message.ink.repository.SyncRepository
import com.message.ink.util.Preferences
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.resume
import com.message.ink.databinding.SettingsControllerBinding

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

    private val signatureSubject: Subject<String> = PublishSubject.create()
    private val autoDeleteSubject: Subject<Int> = PublishSubject.create()
    private val desktopSyncResetSubject: Subject<Unit> = PublishSubject.create()

    private val progressAnimator by lazy { ObjectAnimator.ofInt(binding.syncingProgress, "progress", 0, 0) }

    init {
        appComponent.inject(this)
        retainViewMode = RetainViewMode.RETAIN_DETACH
        layoutRes = R.layout.settings_controller

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

        binding.about.summary = context.getString(R.string.settings_version, BuildConfig.VERSION_NAME)
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.title_settings)
        showBackButton(true)
    }

    override fun preferenceClicks(): Observable<PreferenceView> = (0 until binding.preferences.childCount)
            .map { index -> binding.preferences.getChildAt(index) }
            .mapNotNull { view -> view as? PreferenceView }
            .map { preference -> preference.clicks().map { preference } }
            .let { preferences -> Observable.merge(preferences) }

    override fun aboutLongClicks(): Observable<*> = binding.about.longClicks()

    override fun desktopSyncResetConfirmed(): Observable<*> = desktopSyncResetSubject





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
        binding.desktopSync.summary = state.desktopSyncSummary
        // Nothing to reset until there's a link to reset.
        binding.desktopSync.checkbox.isChecked = state.desktopSyncEnabled
        binding.desktopSyncLink.setVisible(state.desktopSyncEnabled)
        binding.desktopSyncTailscaleOnly.setVisible(state.desktopSyncEnabled)
        binding.desktopSyncTailscaleOnly.checkbox.isChecked = state.desktopSyncTailscaleOnly
        binding.desktopSyncReset.setVisible(state.desktopSyncEnabled)

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

        binding.messsageLinkHandling.summary = state.messageLinkHandlingSummary
        messageLinkHandlingDialog.adapter.selectedItem = state.messageLinkHandlingId

        binding.disableScreenshots.checkbox.isChecked = state.disableScreenshotsEnabled

        when (state.syncProgress) {
            is SyncRepository.SyncProgress.Idle -> binding.syncingProgress.isVisible = false

            is SyncRepository.SyncProgress.Running -> {
                binding.syncingProgress.isVisible = true
                binding.syncingProgress.max = state.syncProgress.max
                progressAnimator.apply { setIntValues(binding.syncingProgress.progress, state.syncProgress.progress) }.start()
                binding.syncingProgress.isIndeterminate = state.syncProgress.indeterminate
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

    override fun showDesktopSyncResetDialog() {
        AlertDialog.Builder(activity!!)
                .setTitle(R.string.settings_desktop_sync_reset_title)
                .setMessage(R.string.settings_desktop_sync_reset_dialog)
                .setPositiveButton(R.string.settings_desktop_sync_reset_confirm) { _, _ ->
                    desktopSyncResetSubject.onNext(Unit)
                    Toast.makeText(activity, R.string.settings_desktop_sync_reset_done, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.button_cancel, null)
                .show()
    }

    override fun showSwipeActions() {
        router.pushController(RouterTransaction.with(SwipeActionsController())
                .pushChangeHandler(QkChangeHandler())
                .popChangeHandler(QkChangeHandler()))
    }

    override fun showAbout() {
        router.pushController(RouterTransaction.with(AboutController())
                .pushChangeHandler(QkChangeHandler())
                .popChangeHandler(QkChangeHandler()))
    }

}