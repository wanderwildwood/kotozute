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
package com.wanderwildwood.kotozute.feature.compose

import com.wanderwildwood.kotozute.common.widget.SelectionMenu
import com.wanderwildwood.kotozute.repository.EmojiReactionRepository
import com.wanderwildwood.kotozute.common.widget.ReactionPicker
import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.format.DateFormat
import android.view.ContextMenu
import android.view.DragEvent.ACTION_DRAG_ENDED
import android.view.DragEvent.ACTION_DRAG_EXITED
import android.view.DragEvent.ACTION_DROP
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.jakewharton.rxbinding2.view.clicks
import com.jakewharton.rxbinding2.widget.textChanges
import com.wanderwildwood.kotozute.common.QkMediaPlayer
import com.uber.autodispose.ObservableSubscribeProxy
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dagger.android.AndroidInjection
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.Navigator
import com.wanderwildwood.kotozute.common.base.QkThemedActivity
import com.wanderwildwood.kotozute.common.util.DateFormatter
import com.wanderwildwood.kotozute.common.util.extensions.autoScrollToStart
import com.wanderwildwood.kotozute.common.util.extensions.dpToPx
import com.wanderwildwood.kotozute.common.util.extensions.hideKeyboard
import com.wanderwildwood.kotozute.common.util.extensions.makeToast
import com.wanderwildwood.kotozute.common.util.extensions.scrapViews
import com.wanderwildwood.kotozute.common.util.extensions.setBackgroundTint
import com.wanderwildwood.kotozute.common.util.extensions.setTint
import com.wanderwildwood.kotozute.common.util.extensions.setVisible
import com.wanderwildwood.kotozute.common.util.extensions.showKeyboard
import com.wanderwildwood.kotozute.common.util.extensions.showCursorWhenWriting
import com.wanderwildwood.kotozute.common.widget.QkEditText
import com.wanderwildwood.kotozute.extensions.mapNotNull
import com.wanderwildwood.kotozute.feature.compose.editing.ChipsAdapter
import com.wanderwildwood.kotozute.feature.contacts.ContactsActivity
import com.wanderwildwood.kotozute.model.Attachment
import com.wanderwildwood.kotozute.model.Recipient
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import com.wanderwildwood.kotozute.databinding.ComposeActivityBinding
import com.wanderwildwood.kotozute.common.util.extensions.turnsAPageOnSwipe
import com.wanderwildwood.kotozute.extensions.isSmil
import com.wanderwildwood.kotozute.extensions.isText
import com.wanderwildwood.kotozute.common.util.einkDialog


class ComposeActivity : QkThemedActivity(), ComposeView {

    private val binding by lazy { ComposeActivityBinding.inflate(layoutInflater) }

    @Inject lateinit var composeAttachmentAdapter: ComposeAttachmentAdapter
    @Inject lateinit var chipsAdapter: ChipsAdapter
    @Inject lateinit var dateFormatter: DateFormatter
    @Inject lateinit var messageAdapter: MessagesAdapter
    @Inject lateinit var navigator: Navigator
    @Inject lateinit var blockingDialog: com.wanderwildwood.kotozute.feature.blocking.BlockingDialog
    @Inject lateinit var deleteConversations: com.wanderwildwood.kotozute.interactor.DeleteConversations

    /** The Signal thread this conversation's contact also has, if any; drives the badge. */
    private var signalThreadKey: String? = null
    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory

    override val activityVisibleIntent: Subject<Boolean> = PublishSubject.create()
    override val chipsSelectedIntent: Subject<HashMap<String, String?>> = PublishSubject.create()
    override val chipDeletedIntent: Subject<Recipient> by lazy { chipsAdapter.chipDeleted }
    override val menuReadyIntent: Observable<Unit> = menu.map { }
    override val optionsItemIntent: Subject<Int> = PublishSubject.create()
    override val contextItemIntent: Subject<MenuItem> = PublishSubject.create()
    override val scheduleAction: Subject<Boolean> = PublishSubject.create()
    override val sendAsGroupIntent by lazy { binding.sendAsGroupBackground.clicks() }
    override val messagePartClickIntent: Subject<Long> by lazy { messageAdapter.partClicks }
    override val messagePartContextMenuRegistrar: Subject<View> by lazy { messageAdapter.partContextMenuRegistrar }
    override val messagesSelectedIntent by lazy { messageAdapter.selectionChanges }
    override val cancelDelayedIntent: Subject<Long> by lazy { messageAdapter.cancelSendingClicks }
    override val sendDelayedNowIntent: Subject<Long> by lazy { messageAdapter.sendNowClicks }
    override val resendIntent: Subject<Long> by lazy { messageAdapter.resendClicks }
    override val reactionPickedIntent: Subject<Triple<Long, String, Boolean>> = PublishSubject.create()
    override val attachmentDeletedIntent: Subject<Attachment> by lazy { composeAttachmentAdapter.attachmentDeleted }
    override val textChangedIntent by lazy { binding.message.textChanges() }
    override val attachIntent: Observable<Unit> by lazy { Observable.merge(binding.attach.clicks(), binding.shadeBackground.clicks()) }
    override val cameraIntent: Observable<Unit> by lazy { Observable.merge(binding.camera.clicks(), binding.cameraLabel.clicks()) }
    override val attachImageFileIntent: Observable<Unit> by lazy { Observable.merge(binding.gallery.clicks(), binding.galleryLabel.clicks()) }
    override val attachAnyFileIntent: Observable<Unit> by lazy { Observable.merge(binding.attachAFileIcon.clicks(), binding.attachAFileLabel.clicks()) }
    override val scheduleIntent: Observable<Unit> by lazy { Observable.merge(binding.schedule.clicks(), binding.scheduleLabel.clicks()) }
    override val attachContactIntent: Observable<Unit> by lazy { Observable.merge(binding.contact.clicks(), binding.contactLabel.clicks()) }
    override val attachAnyFileSelectedIntent: Subject<Uri> = PublishSubject.create()
    override val contactSelectedIntent: Subject<Uri> = PublishSubject.create()
    override val inputContentIntent by lazy { binding.message.inputContentSelected }
    override val scheduleSelectedIntent: Subject<Long> = PublishSubject.create()
    override val changeSimIntent by lazy { binding.sim.clicks() }
    override val scheduleCancelIntent by lazy { binding.scheduledCancel.clicks() }
    override val sendIntent by lazy {  Observable.merge(binding.send.clicks(), binding.scheduledSend.clicks()) }
    override val backPressedIntent: Subject<Unit> = PublishSubject.create()
    override val confirmDeleteIntent: Subject<List<Long>> = PublishSubject.create()
    override val clearCurrentMessageIntent: Subject<Boolean> = PublishSubject.create()
    override val messageLinkAskIntent: Subject<Uri> by lazy { messageAdapter.messageLinkClicks }
    override val shadeIntent by lazy { binding.shadeBackground.clicks() }
    override val recordAudioStartStopRecording: Subject<Boolean> = PublishSubject.create()
    override val recordAnAudioMessage: Observable<Unit> by lazy {
        Observable.merge(binding.recordAudioMsg.clicks(),
            binding.attachAnAudioMessageIcon.clicks(),
            binding.attachAnAudioMessageLabel.clicks())
    }
    override val recordAudioAbort by lazy { binding.audioMsgAbort.clicks() }
    override val recordAudioAttach by lazy { binding.audioMsgAttach.clicks() }
    override val recordAudioPlayerPlayPause: Subject<QkMediaPlayer.PlayingState> = PublishSubject.create()
    override val recordAudioPlayerConfigUI: Subject<QkMediaPlayer.PlayingState> = PublishSubject.create()
    override val recordAudioPlayerVisible: Subject<Boolean> = PublishSubject.create()
    override val recordAudioMsgRecordVisible: Subject<Boolean> = PublishSubject.create()
    override val recordAudioChronometer: Subject<Boolean> = PublishSubject.create()
    override val recordAudioRecord: Subject<Boolean> = PublishSubject.create()
    private var isRecording = false

    private var seekBarUpdater: Disposable? = null

    private val viewModel by lazy { ViewModelProviders.of(this, viewModelFactory)[ComposeViewModel::class.java] }

    private var cameraDestination: Uri? = null

    private val pickMedia = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        uris.forEach(attachAnyFileSelectedIntent::onNext)
    }

    private val pickFilesWithDocsUI = registerForActivityResult(
        com.wanderwildwood.kotozute.common.util.FilePicker.Contract { prefs.filePicker.get() }
    ) { uris ->
        uris.forEach(attachAnyFileSelectedIntent::onNext)
    }

    private val pickContact = registerForActivityResult(
        com.wanderwildwood.kotozute.common.util.ContactsApp.PickContact()
    ) { uri ->
        uri?.let(contactSelectedIntent::onNext)
    }

    private fun getSeekBarUpdater(): ObservableSubscribeProxy<Long> {
        return Observable.interval(500, TimeUnit.MILLISECONDS)
            .subscribeOn(Schedulers.single())
            .observeOn(AndroidSchedulers.mainThread())
            .autoDisposable(scope())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        showBackButton(true)

        // Set title immediately from intent to prevent flash
        intent.getStringExtra("title")?.let { title = it }

        viewModel.bindView(this)

            chipsAdapter.view = binding.chips

            binding.chips.itemAnimator = null
            binding.chips.layoutManager = FlexboxLayoutManager(this)

            messageAdapter.autoScrollToStart(binding.messageList)

            binding.messageList.setHasFixedSize(true)
            binding.messageList.setItemViewCacheSize(20)
            (binding.messageList.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
            binding.messageList.adapter = messageAdapter
            // Which message is chosen, for Pin and Unpin. Subscribed before the view model is
            // bound, so it has heard of a selection by the time the screen redraws for it.
            messageAdapter.selectionChanges.autoDisposable(scope()).subscribe { currentSelection = it }
            // The thread turns a page the way the conversation list does. See the extension:
            // it moves by pixels, so a bubble taller than the screen takes two pages and a
            // picture still decoding cannot throw it off.
            binding.messageList.turnsAPageOnSwipe()

            // NOTE: switching the panel to EinkDisplayMode.FAST while this list scrolls was
            // tried and deliberately REVERTED (2026-08-07). The meink service does accept a
            // non-privileged caller and the mode really does apply — but it bought no
            // measurable improvement (jank across five samples was indistinguishable from
            // AUTO), and the fast waveform's ghosting made it visibly worse to read. The
            // scroll is blocked in dequeueBuffer waiting on the panel, and a cheaper
            // waveform doesn't change how long the panel needs. EinkDisplayMode is kept for
            // its reverse-engineering notes and in case a future need justifies it.

            binding.messageAttachments.adapter = composeAttachmentAdapter

            binding.message.supportsInputContent = true

            binding.message.showCursorWhenWriting()

            // The selection bar's overflow, which the Kompakt never draws: Select all, Share,
            // Define and the other apps that act on text, behind a ⋮.
            SelectionMenu.fold(binding.message, getString(R.string.signal_message_share))

            binding.railBadge.setOnClickListener {
                signalThreadKey?.let { key ->
                    navigator.showSignalThread(key, "")
                    // Replace this screen rather than stack on it -- see the matching note in
                    // SignalThreadActivity. Back from a conversation means the list.
                    finish()
                }
            }

            theme
                .doOnNext {
                    // Set binding.toolbar navigation icon (back arrow) and overflow menu to black
                    binding.toolbar.navigationIcon?.setTint(android.graphics.Color.BLACK)
                    binding.toolbar.overflowIcon?.setTint(android.graphics.Color.BLACK)

                    // Tint all binding.toolbar menu icons to black
                    for (i in 0 until binding.toolbar.menu.size()) {
                        binding.toolbar.menu.getItem(i)?.icon?.setTint(android.graphics.Color.BLACK)
                    }

                    // entire binding.attach menu - white background with black outline, black icons/text
                    binding.attach.setTint(android.graphics.Color.BLACK)
                    binding.contact.setTint(android.graphics.Color.BLACK)
                    binding.contactLabel.setTextColor(android.graphics.Color.BLACK)
                    binding.schedule.setTint(android.graphics.Color.BLACK)
                    binding.scheduleLabel.setTextColor(android.graphics.Color.BLACK)
                    binding.attachAFileIcon.setTint(android.graphics.Color.BLACK)
                    binding.attachAFileLabel.setTextColor(android.graphics.Color.BLACK)
                    binding.attachAnAudioMessageIcon.setTint(android.graphics.Color.BLACK)
                    binding.attachAnAudioMessageLabel.setTextColor(android.graphics.Color.BLACK)
                    binding.gallery.setTint(android.graphics.Color.BLACK)
                    binding.galleryLabel.setTextColor(android.graphics.Color.BLACK)
                    binding.camera.setTint(android.graphics.Color.BLACK)
                    binding.cameraLabel.setTextColor(android.graphics.Color.BLACK)

                    // audio binding.message recording
                    binding.audioMsgPlayerPlayPause.setTint(it.theme)
                    binding.audioMsgPlayerSeekBar.apply {
                        thumbTintList = ColorStateList.valueOf(it.theme)
                        progressBackgroundTintList = ColorStateList.valueOf(it.theme)
                        progressTintList = ColorStateList.valueOf(it.theme)
                    }

                    messageAdapter.theme = it
                }
                .autoDisposable(scope())
                .subscribe()

            // context menu registration for binding.message parts
            messagePartContextMenuRegistrar
                .mapNotNull { it }
                .autoDisposable(scope())
                .subscribe { registerForContextMenu(it) }


            // start/stop audio binding.message recording
            binding.audioMsgRecord.setOnClickListener {
                isRecording = !isRecording
                recordAudioRecord.onNext(isRecording)
                // Update icon based on state
                if (isRecording) {
                    binding.audioMsgRecord.setImageResource(R.drawable.exo_icon_stop)
                } else {
                    binding.audioMsgRecord.setImageResource(android.R.drawable.ic_btn_speak_now)
                }
            }

            recordAudioChronometer
                .subscribeOn(AndroidSchedulers.mainThread())
                .distinctUntilChanged()
                .autoDisposable(scope())
                .subscribe {
                    if (it) {
                        binding.audioMsgDuration.base = SystemClock.elapsedRealtime()
                        binding.audioMsgDuration.start()
                    } else {
                        binding.audioMsgDuration.stop()
                    }
                }

            // audio record playback play/pause button
            binding.audioMsgPlayerPlayPause.setOnClickListener {
                recordAudioPlayerPlayPause.onNext(
                    binding.audioMsgPlayerPlayPause.tag as QkMediaPlayer.PlayingState
                )
            }

            recordAudioMsgRecordVisible
                .subscribeOn(AndroidSchedulers.mainThread())
                .distinctUntilChanged()
                .autoDisposable(scope())
                .subscribe {
                    binding.audioMsgRecord.isVisible = it
                    binding.audioMsgDuration.isVisible =
                        it   // chronometer follows record button visibility
                    binding.audioMsgBluetooth.isVisible = !it
                }

            recordAudioPlayerVisible
                .subscribeOn(AndroidSchedulers.mainThread())
                .distinctUntilChanged()
                .autoDisposable(scope())
                .subscribe {
                    binding.audioMsgPlayerBackground.isVisible = it
                    recordAudioPlayerConfigUI.onNext(QkMediaPlayer.PlayingState.Stopped)
                }

            recordAudioPlayerConfigUI
                .subscribeOn(AndroidSchedulers.mainThread())
                .distinctUntilChanged()
                .autoDisposable(scope())
                .subscribe {
                    when (it) {
                        QkMediaPlayer.PlayingState.Playing -> {
                            binding.audioMsgPlayerPlayPause.tag = QkMediaPlayer.PlayingState.Playing
                            QkMediaPlayer.start()
                            binding.audioMsgPlayerPlayPause.setImageResource(R.drawable.exo_icon_pause)
                            seekBarUpdater = getSeekBarUpdater().subscribe {
                                binding.audioMsgPlayerSeekBar.progress = QkMediaPlayer.currentPosition
                                binding.audioMsgPlayerSeekBar.max = QkMediaPlayer.duration
                            }
                            binding.audioMsgPlayerSeekBar.isEnabled = true
                        }

                        QkMediaPlayer.PlayingState.Paused -> {
                            binding.audioMsgPlayerPlayPause.tag = QkMediaPlayer.PlayingState.Paused
                            QkMediaPlayer.pause()
                            binding.audioMsgPlayerPlayPause.setImageResource(R.drawable.exo_icon_play)
                            seekBarUpdater?.dispose()
                        }

                        else -> {
                            binding.audioMsgPlayerPlayPause.tag = QkMediaPlayer.PlayingState.Stopped
                            QkMediaPlayer.reset()
                            binding.audioMsgPlayerPlayPause.setImageResource(R.drawable.exo_icon_play)
                            seekBarUpdater?.dispose()
                            binding.audioMsgPlayerSeekBar.progress = 0
                            binding.audioMsgPlayerSeekBar.isEnabled = false
                        }
                    }
                }
            // audio msg player seek bar handler
            binding.audioMsgPlayerSeekBar.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(p0: SeekBar?, progress: Int, fromUser: Boolean) {
                        // if seek was initiated by the user and this part is currently playing
                        if (fromUser)
                            QkMediaPlayer.seekTo(progress)
                    }
                    override fun onStartTrackingTouch(p0: SeekBar?) {}
                    override fun onStopTrackingTouch(p0: SeekBar?) {}
                }
            )

            window.callback = ComposeWindowCallback(window.callback, this)
    }

    override fun onStart() {
        super.onStart()
        activityVisibleIntent.onNext(true)
    }

    override fun onPause() {
        super.onPause()
        activityVisibleIntent.onNext(false)
    }

    override fun onDestroy() {
        super.onDestroy()

        // stop any playing audio
        QkMediaPlayer.reset()

        seekBarUpdater?.dispose()
    }


    /**
     * A message request's bar over the composer, until it is answered. See [SmsRequests]:
     * nobody in the address book, and nothing ever sent here.
     */
    private fun showRequest(state: ComposeState) {
        val conversation = state.messages?.first
        val request = conversation != null && state.threadId != 0L && !state.editingMode &&
            SmsRequests.isRequest(prefs, conversation)
        binding.requestBar.setVisible(request)
        // The bar stands taller than the composer it covers; the list makes room for the
        // difference, or the newest message -- the one being asked about -- sits under it.
        binding.requestBar.post {
            val covered = if (binding.requestBar.isShown) {
                binding.messageList.bottom - binding.requestBar.top
            } else 0
            binding.messageList.setPadding(
                binding.messageList.paddingLeft, binding.messageList.paddingTop,
                binding.messageList.paddingRight, covered.coerceAtLeast(0)
            )
            binding.messageList.clipToPadding = covered <= 0
        }
        if (!request) return
        val threadId = state.threadId
        binding.requestAccept.setOnClickListener {
            SmsRequests.accept(prefs, threadId)
            binding.requestBar.setVisible(false)
        }
        binding.requestBlock.setOnClickListener { blockingDialog.show(this, listOf(threadId), true) }
        binding.requestDelete.setOnClickListener {
            einkDialog()
                .setMessage(R.string.signal_request_delete_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.signal_request_delete) { _, _ ->
                    deleteConversations.execute(listOf(threadId))
                    finish()
                }
                .show()
        }
    }

    override fun render(state: ComposeState) {
        if (state.hasError) {
            finish()
            return
        }

        threadId.onNext(state.threadId)
        showRequest(state)

        title = when {
            state.selectedMessages > 0 -> getString(R.string.compose_title_selected, state.selectedMessages)
            state.query.isNotEmpty() -> state.query
            else -> state.conversationtitle
        }

        binding.toolbarSubtitle.setVisible(state.query.isNotEmpty())
        binding.toolbarSubtitle.text = getString(R.string.compose_subtitle_results, state.searchSelectionPosition,
            state.searchResults)

        binding.toolbarTitle.setVisible(!state.editingMode)
        binding.chips.setVisible(state.editingMode)
        binding.composeBar.setVisible(!state.loading)

        // Don't set the adapters unless needed
        if (state.editingMode && binding.chips.adapter == null) binding.chips.adapter = chipsAdapter

        binding.toolbar.menu.findItem(R.id.mute)?.isVisible =
            !state.editingMode && state.selectedMessages == 0 && !state.muted
        binding.toolbar.menu.findItem(R.id.unmute)?.isVisible =
            !state.editingMode && state.selectedMessages == 0 && state.muted
        binding.toolbar.menu.findItem(R.id.viewScheduledMessages)?.isVisible = !state.editingMode && state.selectedMessages == 0
                && state.query.isEmpty() && state.hasScheduledMessages
        binding.toolbar.menu.findItem(R.id.select_all)?.isVisible = !state.editingMode && (messageAdapter.itemCount > 1) && state.selectedMessages != 0
        binding.toolbar.menu.findItem(R.id.add)?.isVisible = state.editingMode
        binding.toolbar.menu.findItem(R.id.call)?.isVisible = !state.editingMode && state.selectedMessages == 0
        binding.toolbar.menu.findItem(R.id.addContact)?.isVisible =
            !state.editingMode && state.selectedMessages == 0 && state.canAddContact
        // The crossing to this person's Signal thread, and the only way to it: an overflow
        // item for the same thing would be a second door to one room, and the buried one.
        //
        // While composing, the same badge is how a Signal conversation gets started at all:
        // choose one person, and if Signal knows them it appears.
        signalThreadKey = if (state.editingMode) state.composeSignalThreadKey else state.signalThreadKey
        binding.railBadge.setVisible(
            state.selectedMessages == 0 && signalThreadKey != null && state.query.isEmpty()
        )
        binding.toolbar.menu.findItem(R.id.info)?.isVisible = !state.editingMode && state.selectedMessages == 0
                && state.query.isEmpty()
        binding.toolbar.menu.findItem(R.id.copy)?.isVisible =
            !state.editingMode && state.selectedMessages > 0 && state.selectedMessagesHaveText
        binding.toolbar.menu.findItem(R.id.share)?.isVisible =
            !state.editingMode && state.selectedMessages > 0 && state.selectedMessagesHaveText
        binding.toolbar.menu.findItem(R.id.details)?.isVisible = !state.editingMode && state.selectedMessages == 1
        // Pin or unpin the one message chosen. See SmsPins.
        val chosen = if (!state.editingMode && state.selectedMessages == 1) selectedMessage() else null
        val chosenPinned = chosen != null && SmsPins.isPinned(prefs, state.threadId, SmsPins.keyOf(chosen))
        binding.toolbar.menu.findItem(R.id.pinMessage)?.isVisible = chosen != null && !chosenPinned
        binding.toolbar.menu.findItem(R.id.unpinMessage)?.isVisible = chosen != null && chosenPinned
        showPinned(state.threadId)
        binding.toolbar.menu.findItem(R.id.delete)?.isVisible = !state.editingMode && ((state.selectedMessages > 0) || state.canSend)
        // A picture with no text can take one too: it goes as "Loved an image".
        binding.toolbar.menu.findItem(R.id.react)?.isVisible =
            chosen != null && (state.selectedMessagesHaveText || chosen.parts.any { !it.isSmil() && !it.isText() })
        binding.toolbar.menu.findItem(R.id.forward)?.isVisible = !state.editingMode && state.selectedMessages == 1
        binding.toolbar.menu.findItem(R.id.show_status)?.isVisible = !state.editingMode && state.selectedMessages > 0
        binding.toolbar.menu.findItem(R.id.previous)?.isVisible = state.selectedMessages == 0 && state.query.isNotEmpty()
        binding.toolbar.menu.findItem(R.id.next)?.isVisible = state.selectedMessages == 0 && state.query.isNotEmpty()
        binding.toolbar.menu.findItem(R.id.clear)?.isVisible = state.selectedMessages == 0 && state.query.isNotEmpty()

        // Tint all visible menu icons to black
        for (i in 0 until binding.toolbar.menu.size()) {
            binding.toolbar.menu.getItem(i)?.icon?.setTint(android.graphics.Color.BLACK)
        }
        binding.toolbar.overflowIcon?.setTint(android.graphics.Color.BLACK)

        chipsAdapter.data = state.selectedChips

        binding.loading.setVisible(state.loading)

        // Always hide the binding.send as group switch - it should always be enabled
        binding.sendAsGroup.setVisible(false)
        binding.sendAsGroupSwitch.isChecked = true  // Always binding.send as group when multiple recipients
        binding.sendAsGroupSummary.setText(R.string.compose_send_group_summary_on)

        binding.messageList.setVisible(!state.editingMode || state.sendAsGroup || state.selectedChips.size == 1)
        messageAdapter.data = state.messages
        messageAdapter.highlight = state.searchSelectionId

        binding.scheduledGroup.isVisible = state.scheduled != 0L
        binding.scheduledTime.text = dateFormatter.getScheduledTimestamp(state.scheduled)

        binding.messageAttachments.setVisible(state.attachments.isNotEmpty())
        composeAttachmentAdapter.data = state.attachments

        binding.attach.rotation = if (state.attaching) 135f else 0f
        binding.attaching.isVisible = state.attaching

        binding.shadeBackground.apply {
            when {
                state.attaching -> {
                    visibility = View.VISIBLE
                    elevation = 4.dpToPx(context).toFloat() // below binding.attach menu
                }

                state.audioMsgRecording -> {
                    visibility = View.VISIBLE
                    elevation = 5.dpToPx(context).toFloat() // above binding.attach menu
                }

                else-> visibility = View.GONE
            }
        }

        // show or hide audio binding.message recording panel and shade background
        binding.audioMsgBackground.isVisible = state.audioMsgRecording

        binding.counter.text = state.remaining
        binding.counter.setVisible(binding.counter.text.isNotBlank())

        // The number alone cannot say why it appeared early, and "70" means nothing to
        // somebody who has always had 160.
        binding.encodingNotice.setVisible(state.wideCharacters)

        binding.sim.setVisible(state.subscription != null)
        binding.sim.contentDescription = getString(R.string.compose_sim_cd, state.subscription?.displayName)
        binding.simIndex.text = state.subscription?.simSlotIndex?.plus(1)?.toString()

        // show either binding.send, audio msg record, or sendScheduled button
        binding.send.visibility = if (state.canSend && !state.loading && state.scheduled == 0L) View.VISIBLE else View.INVISIBLE
        binding.recordAudioMsg.visibility = if (state.canSend && !state.loading) View.INVISIBLE else View.VISIBLE
        binding.scheduledSend.visibility = if (state.canSend && (state.scheduled != 0L) && !state.loading) View.VISIBLE else View.INVISIBLE

        // if not in editing mode, and there are no non-me participants that can be sent to,
        // hide controls that allow constructing a reply and inform user no valid recipients
        if (!state.editingMode && (state.validRecipientNumbers == 0)) {
            binding.composeBar.visibility = View.GONE
            binding.sim.visibility = View.GONE
            binding.recordAudioMsg.visibility = View.GONE
            binding.noValidRecipients.visibility = View.VISIBLE

            // change constraint of binding.messageList to constrain bottom to top of binding.noValidRecipients
            ConstraintSet().apply {
                clone(binding.contentView)
                connect(
                    R.id.messageList,
                    ConstraintSet.BOTTOM,
                    R.id.noValidRecipients,
                    ConstraintSet.TOP,
                    0
                )
                applyTo(binding.contentView)
            }
        }

        // if scheduling mode is set, show binding.schedule dialog
        if (state.scheduling)
            scheduleAction.onNext(true)
    }

    override fun clearSelection() = messageAdapter.clearSelection()

    override fun toggleSelectAll() {
        messageAdapter.toggleSelectAll()
    }

    override fun expandMessages(messageIds: List<Long>, expand: Boolean) {
        messageAdapter.expandMessages(messageIds, expand)
    }

    override fun showDetails(details: String) {
        einkDialog()
            .setTitle(R.string.compose_details_title)
            .setMessage(details)
            .setCancelable(true)
            .show()
    }

    override fun showReactionPicker(messageId: Long, mine: String) =
        ReactionPicker.show(this, EmojiReactionRepository.SMS_CHOICES, mine) { emoji, remove ->
            reactionPickedIntent.onNext(Triple(messageId, emoji, remove))
        }

    override fun showReactionFailed() =
        Toast.makeText(this, R.string.signal_reaction_failed, Toast.LENGTH_SHORT).show()

    override fun showMessageLinkAskDialog(uri: Uri) {
        einkDialog()
            .setTitle(R.string.messageLinkHandling_dialog_title)
            .setMessage(getString(R.string.messageLinkHandling_dialog_body, uri.toString()))
            .setPositiveButton(
                R.string.messageLinkHandling_dialog_positive
            ) { _, _ ->
                ContextCompat.startActivity(
                    this,
                    Intent(Intent.ACTION_VIEW).setData(uri),
                    null
                )
            }
            .setNegativeButton(R.string.messageLinkHandling_dialog_negative) { _, _ -> { } }
            .show()
    }

    override fun requestDefaultSms() {
        navigator.showDefaultSmsDialog(this)
    }

    override fun requestStoragePermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 0)
    }

    override fun requestRecordAudioPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 0)
    }

    override fun requestSmsPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS), 0)
    }

    override fun requestDatePicker() {
        val calendar = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            TimePickerDialog(this, { _, hour, minute ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, day)
                calendar.set(Calendar.HOUR_OF_DAY, hour)
                calendar.set(Calendar.MINUTE, minute)
                scheduleSelectedIntent.onNext(calendar.timeInMillis)
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), DateFormat.is24HourFormat(this))
                .show()
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()

        // On some devices, the keyboard can cover the date picker
        binding.message.hideKeyboard()
    }

    override fun requestContact() {
        pickContact.launch(null)
    }

    override fun showContacts(sharing: Boolean, chips: List<Recipient>) {
        binding.message.hideKeyboard()
        val serialized = HashMap(chips.associate { chip -> chip.address to chip.contact?.lookupKey })
        val intent = Intent(this, ContactsActivity::class.java)
            .putExtra(ContactsActivity.SHARING_KEY, sharing)
            .putExtra(ContactsActivity.CHIPS_KEY, serialized)
        startActivityForResult(intent, ComposeView.SELECT_CONTACT_REQUEST_CODE)
    }

    override fun themeChanged() {
        binding.messageList.scrapViews()
    }

    override fun showKeyboard() {
        binding.message.postDelayed({
            binding.message.showKeyboard()
        }, 200)
    }

    override fun requestCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        val grants =
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION

        var targets = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        var nameTheTarget = false

        if (targets.isEmpty()) {
            // MuditaOS will not resolve a capture intent implicitly, though its
            // camera apps do honour one when addressed directly. Fall back to
            // whatever the system will admit is a camera and name it outright.
            targets = packageManager.queryIntentActivities(
                Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
                PackageManager.MATCH_DEFAULT_ONLY)
            nameTheTarget = true
        }

        if (targets.isEmpty()) {
            makeToast(R.string.compose_camera_unavailable)
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

        // TITLE alone leaves the row without a name or a type, which the media
        // store is free to reject on newer versions.
        val destination = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.TITLE, timestamp)
                put(MediaStore.Images.Media.DISPLAY_NAME, "$timestamp.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            })

        cameraDestination = destination

        if (destination == null) {
            // Without a destination the camera has nowhere to put the photo and
            // the result comes back empty. Say so rather than open it.
            makeToast(R.string.compose_camera_error)
            return
        }

        intent.putExtra(MediaStore.EXTRA_OUTPUT, destination).addFlags(grants)

        // The row belongs to this app, so the camera cannot write to it on the
        // strength of the flags alone -- every app that might answer has to be
        // granted access by name. Without this the capture fails, which reads as
        // a permissions problem with the camera itself.
        targets.forEach { resolveInfo ->
            grantUriPermission(resolveInfo.activityInfo.packageName, destination, grants)
        }

        // A chooser is no use when the system denies that any of these handle a
        // capture -- it would come up empty -- so the target has to be named.
        val toStart = when {
            nameTheTarget -> intent.setClassName(
                targets[0].activityInfo.packageName, targets[0].activityInfo.name)
            else -> Intent.createChooser(intent, null)
        }

        try {
            startActivityForResult(toStart, ComposeView.TAKE_PHOTOS_REQUEST_CODE)
        } catch (e: ActivityNotFoundException) {
            makeToast(R.string.compose_camera_unavailable)
        }
    }

    override fun requestGallery() {
        // A files app chosen in Settings is asked for pictures too; otherwise Android's own
        // photo picker, which only Android can provide.
        if (prefs.filePicker.get().isNotBlank()) {
            pickFilesWithDocsUI.launch("image/*,video/*" to true)
            return
        }
        pickMedia.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
        )
    }

    override fun requestFilePicker() {
        // Whichever app Settings names -- Android's own picker unless another is chosen. The
        // chooser this used to open is what the setting replaced: it left Android's picker
        // out, and offered the gallery and the music player as ways to attach "any file".
        pickFilesWithDocsUI.launch("*/*" to true)
    }

    override fun setDraft(draft: String) {
        binding.message.setText(draft)
        binding.message.setSelection(draft.length)
    }

    override fun scrollToMessage(id: Long) {
        messageAdapter.data?.second
            ?.indexOfLast { message -> message.id == id }
            ?.takeIf { position -> position != -1 }
            ?.let(binding.messageList::scrollToPosition)
    }

    override fun showDeleteDialog(messages: List<Long>) {
        val count = messages.size
        einkDialog()
            .setTitle(R.string.dialog_delete_title)
            .setMessage(resources.getQuantityString(R.plurals.dialog_delete_chat, count, count))
            .setPositiveButton(R.string.button_delete) { _, _ -> confirmDeleteIntent.onNext(messages) }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    override fun showClearCurrentMessageDialog() {
        einkDialog()
            .setTitle(R.string.dialog_clear_compose_title)
            .setMessage(R.string.dialog_clear_compose)
            .setPositiveButton(R.string.button_clear) { _, _ ->
                clearCurrentMessageIntent.onNext(true)
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.compose, menu)
        return super.onCreateOptionsMenu(menu)
    }

    /** The one message selected, when one is. */
    private fun selectedMessage(): com.wanderwildwood.kotozute.model.Message? {
        val id = currentSelection.singleOrNull() ?: return null
        return messageAdapter.data?.second?.firstOrNull { it.isValid && it.id == id }
    }

    /** What the adapter has selected, as it last said. */
    private var currentSelection: List<Long> = emptyList()

    /**
     * The pinned banner, as the Signal thread draws it: the newest pin and how many; a tap
     * goes to it and on to the next, a long press unpins it.
     */
    private var pinnedIndex = 0

    private fun showPinned(threadId: Long) {
        val messages = messageAdapter.data?.second
        val keys = if (threadId == 0L) emptyList() else SmsPins.pinned(prefs, threadId)
        val pinned = keys.mapNotNull { k -> messages?.firstOrNull { it.isValid && SmsPins.keyOf(it) == k } }
        binding.pinnedBar.setVisible(pinned.isNotEmpty())
        if (pinned.isEmpty()) return
        if (pinnedIndex >= pinned.size) pinnedIndex = 0
        val m = pinned[pinnedIndex]
        val preview = m.getText().ifBlank { getString(R.string.signal_pinned_attachment) }.replace('\n', ' ')
        binding.pinnedBar.text = if (pinned.size == 1) getString(R.string.signal_pinned_one, preview)
        else getString(R.string.signal_pinned_many, pinnedIndex + 1, pinned.size, preview)
        val id = m.id
        val key = SmsPins.keyOf(m)
        binding.pinnedBar.setOnClickListener {
            scrollToMessage(id)
            pinnedIndex = (pinnedIndex + 1) % pinned.size
            showPinned(threadId)
        }
        binding.pinnedBar.setOnLongClickListener {
            SmsPins.unpin(prefs, threadId, key)
            showPinned(threadId)
            true
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Pins are this screen's own, kept beside the messages; nothing else needs to hear of them.
        if (item.itemId == R.id.pinMessage || item.itemId == R.id.unpinMessage) {
            val m = selectedMessage() ?: return true
            val threadId = m.threadId
            if (item.itemId == R.id.pinMessage) SmsPins.pin(prefs, threadId, SmsPins.keyOf(m))
            else SmsPins.unpin(prefs, threadId, SmsPins.keyOf(m))
            pinnedIndex = 0
            messageAdapter.clearSelection()
            showPinned(threadId)
            return true
        }
        optionsItemIntent.onNext(item.itemId)
        return true
    }

    override fun getColoredMenuItems(): List<Int> {
        return super.getColoredMenuItems() + R.id.call
    }

    override fun onCreateContextMenu(
        menu: ContextMenu?,
        v: View?,
        menuInfo: ContextMenu.ContextMenuInfo?
    ) {
        super.onCreateContextMenu(menu, v, menuInfo)
        // A long press on a picture opens this menu rather than selecting the message, so the
        // toolbar's React never appears for one. Offered here as well, first, as on Signal.
        menu?.add(Menu.NONE, R.id.react, Menu.NONE, R.string.signal_react)
        menuInflater.inflate(R.menu.mms_part_menu, menu)
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        super.onContextItemSelected(item)
        contextItemIntent.onNext(item)
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK)
            return

        when (requestCode) {
            ComposeView.SELECT_CONTACT_REQUEST_CODE -> {
                // Someone chosen on Signal rather than a recipient for this composer. Replace
                // this screen rather than stack on it, the same way the rail badge does: back
                // from a conversation means the conversation list, not a new message nobody
                // asked to keep.
                val signalThreadKey = data?.getStringExtra(ContactsActivity.SIGNAL_THREAD_KEY)
                if (!signalThreadKey.isNullOrBlank()) {
                    navigator.showSignalThread(
                        signalThreadKey,
                        data.getStringExtra(ContactsActivity.SIGNAL_THREAD_TITLE).orEmpty()
                    )
                    finish()
                    return
                }

                chipsSelectedIntent.onNext(data?.getSerializableExtra(ContactsActivity.CHIPS_KEY)
                    ?.let { serializable -> serializable as? HashMap<String, String?> }
                    ?: hashMapOf())
            }

            ComposeView.TAKE_PHOTOS_REQUEST_CODE -> {
                cameraDestination?.let(attachAnyFileSelectedIntent::onNext)
            }

            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable(ComposeView.CAMERA_DESTINATION_KEY, cameraDestination)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        cameraDestination = savedInstanceState.getParcelable(ComposeView.CAMERA_DESTINATION_KEY)
        super.onRestoreInstanceState(savedInstanceState)
    }

    override fun onBackPressed() {
        backPressedIntent.onNext(Unit)
    }

    override fun focusMessage() {
        binding.message.requestFocus()
    }
}
