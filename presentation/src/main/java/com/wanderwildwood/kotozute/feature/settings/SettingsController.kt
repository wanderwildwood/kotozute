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

import android.Manifest
import android.animation.ObjectAnimator
import android.content.pm.PackageManager
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import androidx.core.content.ContextCompat
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

private const val PICK_EXPORT_FOLDER = 4803
private const val PICK_BACKUP_FOLDER = 4804

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
    private val signalExportFolderSubject: Subject<String> = PublishSubject.create()
    private val signalBackupFolderSubject: Subject<String> = PublishSubject.create()
    private val signalBackupKeySubject: Subject<Pair<String, String>> = PublishSubject.create()

    /**
     * A result that arrives while nobody is listening.
     *
     * The intents the presenter subscribes to are bound in onAttach and torn down when this
     * screen stops -- which is exactly what opening a picker or a scanner does to it. The
     * result then comes back before the screen is attached again, so a value handed straight
     * to its subject is handed to nothing: the folder was chosen, and no import ran. Held
     * here instead, and given up once there is somebody to give it to.
     */
    private var pendingExportFolder: String? = null
    private var pendingBackupFolder: String? = null
    private val signalUnpairSubject: Subject<Unit> = PublishSubject.create()
    private val signalFetchContactsSubject: Subject<Unit> = PublishSubject.create()

    private val signalDiscoverContactsSubject: Subject<Unit> = PublishSubject.create()
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
        // After the intents are bound, never before: these are the results that came back
        // while this screen was stopped.
        pendingExportFolder?.let { folder ->
            pendingExportFolder = null
            signalExportFolderSubject.onNext(folder)
        }
        pendingBackupFolder?.let { folder ->
            pendingBackupFolder = null
            signalBackupFolderSubject.onNext(folder)
        }
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
    /**
     * Whether the bridge has been asked for on this visit.
     *
     * Deliberately not a preference: it is not a setting, it is "I went looking for it just
     * now". Returning to the screen puts it away again, which is the right default for a
     * feature on its way out.
     */

    /**
     * Whether a bridge is currently paired, as of the last render.
     *
     * Kept because the disclosure is closed from [showSection], which has no state to consult
     * and gets no new one -- nothing about the account changed by moving between sections, so
     * render() is not called again and the rows would keep whatever visibility they were last
     * given by hand.
     */

    private var openSection: Int = 0
    private var openTitle: Int = R.string.title_settings

    override fun showSection(container: Int, title: Int) {
        openSection = container
        openTitle = title
        // Sections swap in place, so this controller outlives any one of them and the
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

    override fun signalExportFolderChosen(): Observable<String> = signalExportFolderSubject

    override fun signalBackupFolderChosen(): Observable<String> = signalBackupFolderSubject

    override fun signalBackupKeyEntered(): Observable<Pair<String, String>> = signalBackupKeySubject

    override fun signalUnpairConfirmed(): Observable<*> = signalUnpairSubject

    override fun signalFetchContactsConfirmed(): Observable<*> = signalFetchContactsSubject

    override fun signalDiscoverContactsConfirmed(): Observable<*> = signalDiscoverContactsSubject

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

        // Linking again is not additive -- it registers a new device and abandons the old
        // one's keys -- so a device that already has a link is told that before it taps.
        binding.signalLink.summary = context.getString(
            if (state.signalLinkedDirectly) R.string.settings_signal_link_relinked
            else R.string.settings_signal_link_summary
        )
        // Only while there is no Signal here yet. Once the phone has an account, this row
        // would be an offer to re-register -- which is the same destructive act again, with
        // nothing gained.
        binding.signalRegister.setVisible(!state.signalLinkedDirectly)

        binding.signalEnabled.setVisible(state.signalPaired)
        binding.signalEnabled.checkbox.isChecked = state.signalEnabled
        binding.signalUnpair.setVisible(state.signalPaired)
        // The status line only means anything once Signal is actually switched on.
        binding.signalOpen.setVisible(state.signalPaired && state.signalEnabled)
        // Not only when a bridge is paired. Importing used to run on the bridge's machine, so
        // the row was only of use to someone who had one; it runs here now, and a linked
        // phone is exactly the case with no history to begin with.
        // Not only when a bridge is paired. Importing used to run on the bridge's machine, so
        // the row was of use only to someone who had one; it runs here now, and a linked
        // phone is exactly the case that starts with no history at all.
        // Not only when a bridge is paired. Importing used to run on the bridge's machine, so
        // these were of use only to someone who had one; they run here now, and a linked
        // phone is exactly the case that starts with no history and holds the only copy of
        // what it has since been given.
        val signalSetUp = (state.signalPaired || state.signalLinkedDirectly) && state.signalEnabled
        binding.signalFetchContacts.setVisible(signalSetUp)
        // Directly linked only. A paired bridge resolves numbers on its own side, so asking
        // the enclave from here would send the address book off the phone to answer a
        // question that is already answered.
        binding.signalDiscoverContacts.setVisible(state.signalLinkedDirectly && state.signalEnabled)
        binding.signalHistoryImport.setVisible(signalSetUp)
        binding.signalHistoryExport.setVisible(signalSetUp)
        binding.signalAccount.setVisible(state.signalPaired && state.signalEnabled)
        binding.signalKeepConnected.setVisible(state.signalPaired && state.signalEnabled)
        binding.signalKeepConnected.checkbox.isChecked = state.signalKeepConnected
        binding.signalWeave.setVisible(state.signalPaired && state.signalEnabled)
        binding.signalWeave.checkbox.isChecked = state.signalWeave
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

    override fun chooseSignalExportFolder() {
        // A folder, not a file: an export is main.jsonl beside the pictures it names, and
        // picking the file alone would import a history with every attachment missing.
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, PICK_EXPORT_FOLDER)
    }

    override fun chooseSignalBackupFolder() {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, PICK_BACKUP_FOLDER)
    }

    override fun askSignalBackupKey(folder: String) {
        activity?.runOnUiThread {
            val activity = activity ?: return@runOnUiThread
            val input = EditText(activity).apply {
                setHint(R.string.settings_signal_backup_key_hint)
                // Digits and the spaces they were written down in. A phone keyboard that
                // opens on letters for a key that has none is a small cruelty.
                inputType = android.text.InputType.TYPE_CLASS_PHONE
                setSingleLine(true)
            }
            AlertDialog.Builder(activity)
                .setTitle(R.string.settings_signal_backup_key_title)
                .setMessage(R.string.settings_signal_backup_key_body)
                .setView(input)
                .setNegativeButton(R.string.button_cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    signalBackupKeySubject.onNext(folder to input.text.toString())
                }
                .show()
        }
    }

    override fun showSignalBackupKeyWrong(folder: String) {
        activity?.runOnUiThread {
            val activity = activity ?: return@runOnUiThread
            binding.signalHistoryImport.summary =
                activity.getString(R.string.settings_signal_import_summary)
            AlertDialog.Builder(activity)
                .setTitle(R.string.settings_signal_backup_key_title)
                .setMessage(R.string.settings_signal_backup_key_wrong)
                .setNegativeButton(R.string.button_cancel, null)
                // The likeliest reason to be here is a digit typed wrong, so the way back is
                // the same dialog rather than the whole journey through the picker again.
                .setPositiveButton(R.string.settings_signal_backup_key_again) { _, _ ->
                    askSignalBackupKey(folder)
                }
                .show()
        }
    }

    override fun showSignalExportProgress(messages: Int) {
        activity?.runOnUiThread {
            binding.signalHistoryExport.summary =
                activity?.getString(R.string.settings_signal_history_writing, messages)
        }
    }

    override fun showSignalExportResult(
        stats: com.wanderwildwood.kotozute.repository.SignalRepository.ExportStats?
    ) {
        activity?.runOnUiThread {
            val activity = activity ?: return@runOnUiThread
            binding.signalHistoryExport.summary =
                activity.getString(R.string.settings_signal_export_summary)
            val message = if (stats == null) {
                activity.getString(R.string.settings_signal_history_not_written)
            } else {
                buildString {
                    append(
                        activity.resources.getQuantityString(
                            R.plurals.settings_signal_history_written, stats.messages, stats.messages
                        )
                    )
                    append('\n').append(
                        activity.getString(R.string.settings_signal_history_written_where, stats.folder)
                    )
                    // Nothing to copy down for a copy locked to the account, which is every
                    // copy written now -- so the screen says what opens it instead of handing
                    // the reader thirty digits to look after.
                    append("\n\n").append(
                        if (stats.key.isBlank()) {
                            activity.getString(R.string.settings_signal_history_written_account)
                        } else {
                            activity.getString(
                                R.string.settings_signal_history_written_key, stats.key
                            )
                        }
                    )
                    if (stats.missing > 0) {
                        append('\n').append(
                            activity.resources.getQuantityString(
                                R.plurals.settings_signal_history_written_missing,
                                stats.missing, stats.missing
                            )
                        )
                    }
                }
            }
            val dialog = AlertDialog.Builder(activity)
                .setTitle(R.string.settings_signal_history_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            // Selectable, because the thirty digits in it are the whole of what makes the
            // copy readable and copying them by hand is how they get written down wrong.
            dialog.findViewById<android.widget.TextView>(android.R.id.message)
                ?.setTextIsSelectable(true)
        }
    }

    /**
     * On the row rather than in a dialog. It is the only thing happening, the reader is
     * looking at the row they just tapped, and a dialog that cannot be dismissed while
     * thousands of messages are read is a locked screen with a number on it.
     */
    /**
     * Asked every time, not once.
     *
     * This is the only thing in the app that sends the address book anywhere, and a row that
     * did it on a single tap would be a row somebody could press without knowing that. The
     * wording says where the numbers go and that Signal rations the asking; the quota is the
     * part that surprises people, because spending it affects the account rather than the app.
     */
    override fun askDiscoverContacts() {
        activity?.runOnUiThread {
            val activity = activity ?: return@runOnUiThread
            AlertDialog.Builder(activity)
                .setTitle(R.string.settings_signal_discover_contacts_confirm_title)
                .setMessage(R.string.settings_signal_discover_contacts_confirm_body)
                .setPositiveButton(R.string.settings_signal_discover_contacts_confirm_yes) { _, _ ->
                    signalDiscoverContactsSubject.onNext(Unit)
                }
                .setNegativeButton(R.string.settings_signal_discover_contacts_confirm_no, null)
                .show()
        }
    }

    override fun askFetchContacts() {
        activity?.runOnUiThread {
            val activity = activity ?: return@runOnUiThread
            AlertDialog.Builder(activity)
                .setTitle(R.string.settings_signal_fetch_contacts_offer_title)
                .setMessage(R.string.settings_signal_fetch_contacts_offer_body)
                .setPositiveButton(R.string.settings_signal_fetch_contacts_offer_yes) { _, _ ->
                    signalFetchContactsSubject.onNext(Unit)
                }
                // Dismissible, and nothing breaks if it is dismissed. The row in this screen
                // does the same thing whenever they want it.
                .setNegativeButton(R.string.settings_signal_fetch_contacts_offer_no, null)
                .show()
        }
    }

    override fun showSignalFetchResult(what: String) {
        activity?.runOnUiThread {
            val activity = activity ?: return@runOnUiThread
            AlertDialog.Builder(activity)
                .setTitle(R.string.settings_signal_fetch_contacts_title)
                .setMessage(what)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    override fun showSignalImportProgress(messages: Int) {
        activity?.runOnUiThread {
            binding.signalHistoryImport.summary =
                activity?.getString(R.string.settings_signal_history_working, messages)
        }
    }

    override fun showSignalImportResult(
        stats: com.wanderwildwood.kotozute.repository.SignalRepository.ImportStats?
    ) {
        activity?.runOnUiThread {
            val activity = activity ?: return@runOnUiThread
            binding.signalHistoryImport.summary =
                activity.getString(R.string.settings_signal_import_summary)
            val message = if (stats == null) {
                activity.getString(R.string.settings_signal_history_not_an_export)
            } else {
                buildString {
                    append(activity.resources.getQuantityString(
                        R.plurals.settings_signal_history_imported, stats.messages, stats.messages
                    ))
                    if (stats.alreadyPresent > 0) {
                        append('\n').append(activity.getString(
                            R.string.settings_signal_history_already, stats.alreadyPresent
                        ))
                    }
                    if (stats.attachments > 0) {
                        append('\n').append(activity.resources.getQuantityString(
                            R.plurals.settings_signal_history_attachments,
                            stats.attachments, stats.attachments
                        ))
                    }
                    if (stats.attachmentsLost > 0) {
                        append('\n').append(activity.resources.getQuantityString(
                            R.plurals.settings_signal_history_attachments_lost,
                            stats.attachmentsLost, stats.attachmentsLost
                        ))
                    }
                    // A message the export could not attribute is the one silence worth
                    // breaking: on an account that knows itself this is always zero, so
                    // seeing it at all means something was lost rather than merely refused.
                    if (stats.skippedNoAuthor > 0) {
                        append('\n').append(
                            activity.resources.getQuantityString(
                                R.plurals.settings_signal_history_no_author,
                                stats.skippedNoAuthor, stats.skippedNoAuthor
                            )
                        )
                    }
                    // Only the skips a person can do something about. The rest -- events,
                    // tombstones, messages whose timer had already run out -- are things
                    // that were never going to be messages here, and listing them reads as
                    // loss where there was none.
                    if (stats.skippedUnknownGroup > 0) {
                        append('\n').append(activity.getString(
                            R.string.settings_signal_history_groups, stats.skippedUnknownGroup
                        ))
                    }
                }
            }
            AlertDialog.Builder(activity)
                .setTitle(R.string.settings_signal_history_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    override fun showSignalAccountDialog(
        account: com.wanderwildwood.kotozute.repository.SignalAccount?
    ) {
        val activity = activity ?: return
        activity.runOnUiThread {
            val message = if (account == null) {
                activity.getString(R.string.signal_account_unreachable)
            } else {
                buildString {
                    append(account.number).append('\n')
                    if (account.selfUuid.isNotBlank()) append(account.selfUuid).append('\n')
                    append('\n')
                    // No device list. The bridge asked signal-cli for one; this phone can
                    // say which device it is on the account and no more, and a list it
                    // cannot check is worse than saying so.
                    append(activity.getString(R.string.signal_account_this_device_is, account.thisDeviceId))
                        .append("\n\n")
                    append(activity.getString(R.string.signal_account_linked_note))
                }
            }
            val dialog = AlertDialog.Builder(activity)
                .setTitle(R.string.settings_signal_account_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            dialog.findViewById<android.widget.TextView>(android.R.id.message)
                ?.setTextIsSelectable(true)
        }
    }

    override fun showDesktopSyncLinkDialog(urls: List<Pair<String, String>>) {
        if (urls.isEmpty()) {
            AlertDialog.Builder(activity!!)
                .setTitle(R.string.settings_desktop_sync_link_title)
                .setMessage(R.string.settings_desktop_sync_link_none)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        // A short code first, because typing the full link is the part people complain
        // about: a host and six digits beats a host, a port and a 24-character token.
        val code = com.wanderwildwood.kotozute.feature.desktopsync.DesktopSyncPairing.issue()
        val hosts = urls.map { (label, url) ->
            label to url.substringBefore("?token=")
        }

        // All of them, labelled. The relay listens on every interface, so on a phone with
        // both Wi-Fi and Tailscale up there is more than one right answer and no way from
        // here to know which the computer can see. Showing one and hiding the rest is what
        // made a wrong address so hard to diagnose: the page just never loaded.
        val message = buildString {
            append(activity!!.getString(R.string.settings_desktop_sync_code_intro)).append("\n\n")
            hosts.forEach { (label, url) -> append(label).append('\n').append(url).append("\n\n") }
            append(activity!!.getString(
                R.string.settings_desktop_sync_code,
                code.substring(0, 3) + " " + code.substring(3)
            ))
            append("\n\n")
            append(activity!!.getString(R.string.settings_desktop_sync_link_full)).append("\n\n")
            urls.forEach { (label, url) -> append(label).append('\n').append(url).append("\n\n") }
            append(activity!!.getString(R.string.settings_desktop_sync_link_bookmark))
        }
        val builder = AlertDialog.Builder(activity!!)
                .setTitle(R.string.settings_desktop_sync_link_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)

        // The link carries a long random token and is read off a phone to be entered on a
        // computer. Without this it can only be copied out by hand, one character at a time.
        // With more than one address the copy button takes the first, which is the tailnet
        // address when there is one; the rest are selectable in the dialog.
        builder.setNeutralButton(R.string.settings_desktop_sync_link_copy) { _, _ ->
            val clipboard = activity!!
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Desktop Sync", urls.first().second))
            Toast.makeText(activity, R.string.settings_desktop_sync_link_copied, Toast.LENGTH_SHORT).show()
        }

        val dialog = builder.show()
        // Selectable as well as copyable, so part of it can be taken if that is what is wanted.
        dialog.findViewById<android.widget.TextView>(android.R.id.message)?.setTextIsSelectable(true)
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

    override fun showSignalLink() {
        activity?.let { it.startActivity(com.wanderwildwood.kotozute.feature.signal.SignalLinkActivity.intent(it)) }
    }

    override fun showSignalRegister() {
        activity?.let { it.startActivity(com.wanderwildwood.kotozute.feature.signal.SignalRegisterActivity.intent(it)) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_EXPORT_FOLDER) {
            // A cancelled pick is not a failure worth saying anything about.
            data?.data?.let { folder -> pendingExportFolder = folder.toString() }
            return
        }
        if (requestCode == PICK_BACKUP_FOLDER) {
            data?.data?.let { folder -> pendingBackupFolder = folder.toString() }
            return
        }
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