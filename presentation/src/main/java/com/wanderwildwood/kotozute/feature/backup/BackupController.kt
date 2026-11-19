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
package com.wanderwildwood.kotozute.feature.backup

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.core.view.children
import androidx.core.view.isVisible
import com.jakewharton.rxbinding2.view.clicks
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.QkDialog
import com.wanderwildwood.kotozute.common.base.QkController
import com.wanderwildwood.kotozute.common.util.FilesOpenTree
import com.wanderwildwood.kotozute.common.util.QkActivityResultContracts
import com.wanderwildwood.kotozute.common.util.extensions.getLabel
import com.wanderwildwood.kotozute.common.util.extensions.setBackgroundTint
import com.wanderwildwood.kotozute.common.util.extensions.setNegativeButton
import com.wanderwildwood.kotozute.common.util.extensions.setPositiveButton
import com.wanderwildwood.kotozute.common.util.extensions.setShowing
import com.wanderwildwood.kotozute.common.util.extensions.setTint
import com.wanderwildwood.kotozute.common.widget.PreferenceView
import com.wanderwildwood.kotozute.injection.appComponent
import com.wanderwildwood.kotozute.repository.BackupRepository
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject
import com.wanderwildwood.kotozute.databinding.BackupControllerBinding
import com.wanderwildwood.kotozute.common.util.einkDialog

class BackupController : QkController<BackupView, BackupState, BackupPresenter>(), BackupView {

    private val binding get() = BackupControllerBinding.bind(containerView!!)

    @Inject override lateinit var presenter: BackupPresenter

    @Inject lateinit var frequencyDialog: QkDialog
    @Inject lateinit var keepDialog: QkDialog

    private val selectFolderCancelSubject: Subject<Unit> = PublishSubject.create()
    private val selectFolderConfirmSubject: Subject<Unit> = PublishSubject.create()

    private val restoreErrorConfirmSubject: Subject<Unit> = PublishSubject.create()

    private val confirmRestoreCancelSubject: Subject<Unit> = PublishSubject.create()
    private val confirmRestoreConfirmSubject: Subject<Unit> = PublishSubject.create()

    private val stopRestoreConfirmSubject: Subject<Unit> = PublishSubject.create()
    private val stopRestoreCancelSubject: Subject<Unit> = PublishSubject.create()

    private val documentTreeSelectedSubject: Subject<Uri> = PublishSubject.create()
    private val documentSelectedSubject: Subject<Uri> = PublishSubject.create()

    private val stopRestoreDialog by lazy {
        activity!!.einkDialog()
                .setTitle(R.string.backup_restore_stop_title)
                .setMessage(R.string.backup_restore_stop_message)
                .setPositiveButton(R.string.button_stop, stopRestoreConfirmSubject)
                .setNegativeButton(R.string.button_cancel, stopRestoreCancelSubject)
                .setCancelable(false)
                .create()
    }

    private val selectLocationRationaleDialog by lazy {
        activity!!.einkDialog()
                .setTitle(R.string.backup_select_location_rationale_title)
                .setMessage(R.string.backup_select_location_rationale_message)
                .setPositiveButton(R.string.button_continue, selectFolderConfirmSubject)
                .setNegativeButton(R.string.button_cancel, selectFolderCancelSubject)
                .setCancelable(false)
                .create()
    }

    private val selectedBackupErrorDialog by lazy {
        activity!!.einkDialog()
                .setTitle(R.string.backup_selected_backup_error_title)
                .setMessage(R.string.backup_selected_backup_error_message)
                .setPositiveButton(R.string.button_continue, restoreErrorConfirmSubject)
                .setCancelable(false)
                .create()
    }

    private val selectedBackupDetailsDialog by lazy {
        activity!!.einkDialog()
                .setTitle(R.string.backup_selected_backup_details_title)
                .setPositiveButton(R.string.backup_restore_title, confirmRestoreConfirmSubject)
                .setNegativeButton(R.string.button_cancel, confirmRestoreCancelSubject)
                .setCancelable(false)
                .create()
    }

    private lateinit var openDirectory: ActivityResultLauncher<Uri?>
    private lateinit var openDocument: ActivityResultLauncher<QkActivityResultContracts.OpenDocumentParams>

    init {
        appComponent.inject(this)
        layoutRes = R.layout.backup_controller
    }

    override fun onContextAvailable(context: Context) {
        // Init activity result contracts
        openDirectory = themedActivity!!
            .registerForActivityResult(FilesOpenTree()) { uri ->
                uri?.let(documentTreeSelectedSubject::onNext)
            }

        openDocument = themedActivity!!
            .registerForActivityResult(QkActivityResultContracts.OpenDocument()) { uri ->
                uri?.let(documentSelectedSubject::onNext)
            }
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.backup_title)
        showBackButton(true)
    }

    override fun onViewCreated() {
        super.onViewCreated()

        themedActivity?.colors?.theme()?.let { theme ->
            binding.fab.setBackgroundTint(theme.theme)
            binding.fabIcon.setTint(theme.textPrimary)
            binding.fabLabel.setTextColor(theme.textPrimary)
        }

        frequencyDialog.adapter.setData(R.array.backup_frequency_labels)
        frequencyDialog.setTitle(R.string.backup_auto_title)
        keepDialog.adapter.setData(R.array.backup_keep_labels, R.array.backup_keep_ids)
        keepDialog.setTitle(R.string.backup_keep_title)

        binding.frequency.setOnClickListener { frequencyDialog.show(activity!!) }
        binding.keep.setOnClickListener { keepDialog.show(activity!!) }
        binding.backupCode.setOnClickListener { codeOptions() }
        showCodeSummary()

        // Make the list titles bold
        binding.linearLayout.children
                .mapNotNull { it as? PreferenceView }
                .map { it.titleView }
                .forEach { it.setTypeface(it.typeface, Typeface.BOLD) }
    }

    override fun render(state: BackupState) {
        val frequencyLabels = activity!!.resources.getStringArray(R.array.backup_frequency_labels)
        binding.frequency.summary = frequencyLabels.getOrElse(state.frequency) { frequencyLabels[0] }
        frequencyDialog.adapter.selectedItem = state.frequency
        binding.keep.isVisible = state.frequency != 0
        binding.keep.summary = activity!!.resources
                .getQuantityString(R.plurals.backup_keep_summary, state.keep, state.keep)
        keepDialog.adapter.selectedItem = state.keep

        when {
            state.backupProgress.running -> {
                binding.progressIcon.setImageResource(R.drawable.ic_file_upload_black_24dp)
                binding.progressTitle.setText(R.string.backup_backing_up)
                binding.progressSummary.text = state.backupProgress.getLabel(activity!!)
                binding.progressSummary.isVisible = binding.progressSummary.text.isNotEmpty()
                binding.progressCancel.isVisible = false
                binding.progress.isVisible = true
                binding.fab.isVisible = false
            }

            state.restoreProgress.running -> {
                binding.progressIcon.setImageResource(R.drawable.ic_file_download_black_24dp)
                binding.progressTitle.setText(R.string.backup_restoring)
                binding.progressSummary.text = state.restoreProgress.getLabel(activity!!)
                binding.progressSummary.isVisible = binding.progressSummary.text.isNotEmpty()
                binding.progressCancel.isVisible = true
                binding.progress.isVisible = true
                binding.fab.isVisible = false
            }

            else -> {
                binding.progress.isVisible = false
                binding.fab.isVisible = true
            }
        }

        if (state.showLocationRationale && !selectLocationRationaleDialog.isShowing) {
            selectLocationRationaleDialog.setTitle(
                if (state.locationGone) R.string.backup_location_gone_title
                else R.string.backup_select_location_rationale_title)
            selectLocationRationaleDialog.setMessage(activity!!.getString(
                if (state.locationGone) R.string.backup_location_gone_message
                else R.string.backup_select_location_rationale_message))
        }
        selectLocationRationaleDialog.setShowing(state.showLocationRationale)

        selectedBackupErrorDialog.setShowing(state.showSelectedBackupError)

        selectedBackupDetailsDialog.setMessage(state.selectedBackupDetails)
        selectedBackupDetailsDialog.setShowing(state.selectedBackupDetails != null)

        stopRestoreDialog.setShowing(state.showStopRestoreDialog)

        binding.fabIcon.setImageResource(R.drawable.ic_file_upload_black_24dp)
        binding.fabLabel.setText(R.string.backup_now)
    }

    override fun setBackupLocationClicks(): Observable<*> = binding.location.clicks()

    override fun frequencySelected(): Observable<Int> = frequencyDialog.adapter.menuItemClicks

    override fun keepSelected(): Observable<Int> = keepDialog.adapter.menuItemClicks

    override fun restoreClicks(): Observable<*> = binding.restore.clicks()

    override fun locationRationaleConfirmClicks(): Observable<*> = selectFolderConfirmSubject

    override fun locationRationaleCancelClicks(): Observable<*> = selectFolderCancelSubject

    override fun selectedBackupErrorClicks(): Observable<*> = restoreErrorConfirmSubject

    override fun confirmRestoreBackupConfirmClicks(): Observable<*> = confirmRestoreConfirmSubject

    override fun confirmRestoreBackupCancelClicks(): Observable<*> = confirmRestoreCancelSubject

    override fun stopRestoreClicks(): Observable<*> = binding.progressCancel.clicks()

    override fun stopRestoreConfirmed(): Observable<*> = stopRestoreConfirmSubject

    override fun stopRestoreCancel(): Observable<*> = stopRestoreCancelSubject

    // No code yet: set one up first (and show it), then the next tap backs up.
    override fun backupClicks(): Observable<*> = binding.fab.clicks()
            .filter {
                val ok = runCatching { com.wanderwildwood.kotozute.backup.BackupCode.exists(activity!!) }.getOrDefault(false)
                if (!ok) offerNewCode()
                ok
            }

    // ---- backup code (BackupCode; Signal-style 30 digits) --------------------------------

    private fun showCodeSummary() {
        val has = com.wanderwildwood.kotozute.backup.BackupCode.exists(activity!!)
        binding.backupCode.summary = activity!!.getString(
            if (has) R.string.backup_code_summary_set else R.string.backup_code_summary_none
        )
    }

    private fun secure(dialog: androidx.appcompat.app.AlertDialog) {
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun codeOptions() {
        val ctx = activity ?: return
        if (!com.wanderwildwood.kotozute.backup.BackupCode.exists(ctx)) {
            ctx.einkDialog()
                .setTitle(R.string.backup_code_title)
                .setItems(arrayOf(ctx.getString(R.string.backup_code_make), ctx.getString(R.string.backup_code_enter))) { _, i ->
                    if (i == 0) offerNewCode() else enterCode()
                }
                .show()
            return
        }
        ctx.einkDialog()
            .setTitle(R.string.backup_code_title)
            .setItems(arrayOf(ctx.getString(R.string.backup_code_show), ctx.getString(R.string.backup_code_enter))) { _, i ->
                if (i == 0) showCode() else enterCode()
            }
            .show()
    }

    private fun offerNewCode() {
        val ctx = activity ?: return
        ctx.einkDialog()
            .setTitle(R.string.backup_code_title)
            .setMessage(R.string.backup_code_make_message)
            .setNegativeButton(R.string.button_cancel, null)
            .setPositiveButton(R.string.backup_code_make) { _, _ ->
                runCatching { com.wanderwildwood.kotozute.backup.BackupCode.create(ctx) }
                    .onSuccess { showCode(it) }
                    .onFailure { timber.log.Timber.w(it, "backup: could not make a code") }
                showCodeSummary()
            }
            .show()
    }

    private fun showCode(known: String? = null) {
        val ctx = activity ?: return
        val code = known ?: runCatching { com.wanderwildwood.kotozute.backup.BackupCode.get(ctx) }.getOrNull() ?: return
        ctx.einkDialog()
            .setTitle(R.string.backup_code_title)
            .setMessage(ctx.getString(R.string.backup_code_show_message, com.wanderwildwood.kotozute.backup.BackupCrypto.grouped(code)))
            .setPositiveButton(R.string.backup_code_written_down, null)
            .show()
            .also(::secure)
    }

    private fun enterCode() {
        val ctx = activity ?: return
        val field = android.widget.EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setHint(R.string.backup_code_enter_hint)
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val box = android.widget.FrameLayout(ctx).apply {
            val pad = (24 * ctx.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(field)
        }
        ctx.einkDialog()
            .setTitle(R.string.backup_code_enter)
            .setMessage(R.string.backup_code_enter_message)
            .setView(box)
            .setNegativeButton(R.string.button_cancel, null)
            .setPositiveButton(R.string.backup_code_keep) { _, _ ->
                val code = com.wanderwildwood.kotozute.backup.BackupCrypto.normalize(field.text.toString())
                field.setText("")
                if (code == null) {
                    ctx.einkDialog().setMessage(R.string.backup_code_not_whole)
                        .setPositiveButton(android.R.string.ok, null).show()
                } else {
                    runCatching { com.wanderwildwood.kotozute.backup.BackupCode.set(ctx, code) }
                        .onFailure { timber.log.Timber.w(it, "backup: could not keep the code") }
                    showCodeSummary()
                }
            }
            .show()
            .also(::secure)
    }

    override fun documentTreeSelected(): Observable<Uri> = documentTreeSelectedSubject

    override fun documentSelected(): Observable<Uri> = documentSelectedSubject

    override fun selectFolder(initialUri: Uri) {
        openDirectory.launch(initialUri)
    }

    override fun selectFile(initialUri: Uri) {
        openDocument.launch(QkActivityResultContracts.OpenDocumentParams(
                mimeTypes = listOf("application/json", "application/octet-stream"),
                initialUri = initialUri))
    }

}
