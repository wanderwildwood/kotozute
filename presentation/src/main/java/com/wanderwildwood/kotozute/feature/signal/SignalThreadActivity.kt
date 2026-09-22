package com.wanderwildwood.kotozute.feature.signal

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.QkMediaPlayer
import com.wanderwildwood.kotozute.common.base.QkThemedActivity
import com.wanderwildwood.kotozute.common.util.extensions.setVisible
import com.wanderwildwood.kotozute.databinding.SignalMessageListItemBinding
import com.wanderwildwood.kotozute.databinding.SignalThreadActivityBinding
import com.wanderwildwood.kotozute.model.SignalMessage
import com.wanderwildwood.kotozute.interactor.UpdateScheduledMessageAlarms
import com.wanderwildwood.kotozute.repository.ScheduledMessageRepository
import com.wanderwildwood.kotozute.repository.SafetyNumberChanged
import com.wanderwildwood.kotozute.repository.SignalRepository
import com.wanderwildwood.kotozute.common.util.DateFormatter
import com.wanderwildwood.kotozute.common.util.MessageLinks
import dagger.android.AndroidInjection
import io.reactivex.disposables.CompositeDisposable
import io.realm.RealmResults
import org.json.JSONArray
import android.util.LruCache
import java.util.Calendar
import javax.inject.Inject
import kotlin.concurrent.thread
import timber.log.Timber
import kotlin.math.abs
import java.util.concurrent.TimeUnit
import com.wanderwildwood.kotozute.common.util.extensions.dpToPx
import com.wanderwildwood.kotozute.feature.compose.BubbleUtils
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.wanderwildwood.kotozute.common.util.TextViewStyler
import com.wanderwildwood.kotozute.common.util.extensions.turnsAPageOnSwipe
import com.wanderwildwood.kotozute.feature.extensions.isEmojiOnly
import com.wanderwildwood.kotozute.common.util.extensions.stopAnimatingItems

class SignalThreadActivity : QkThemedActivity() {

    @Inject lateinit var signalRepo: SignalRepository
    @Inject lateinit var dateFormatter: DateFormatter
    @Inject lateinit var textViewStyler: com.wanderwildwood.kotozute.common.util.TextViewStyler
    @Inject lateinit var notifications: SignalNotifications
    @Inject lateinit var navigator: com.wanderwildwood.kotozute.common.Navigator
    @Inject lateinit var scheduledMessageRepo: ScheduledMessageRepository
    @Inject lateinit var markTextArchived: com.wanderwildwood.kotozute.interactor.MarkArchived
    @Inject lateinit var markTextUnarchived: com.wanderwildwood.kotozute.interactor.MarkUnarchived
    @Inject lateinit var updateScheduledMessageAlarms: UpdateScheduledMessageAlarms

    private lateinit var binding: SignalThreadActivityBinding
    private val disposables = CompositeDisposable()
    private var messages: RealmResults<SignalMessage>? = null
    /** The SMS thread for the same person, when there is one. */
    private var smsThreadId: Long = 0L
    private lateinit var adapter: MessageAdapter

    /**
     * The text conversation this one is joined to, by number or by hand, if any.
     *
     * This screen shows the Signal rail and nothing else -- one screen per rail, the badge
     * between them. The two were briefly merged onto one screen; the trouble with that is
     * that it leaves the badge leading somewhere that looks the same, which is no use to
     * anybody wanting to see one rail on its own.
     */
    private var linkedConversationId: Long? = null

    private var isArchived: Boolean = false
    private var isPinned: Boolean = false
    private var isMuted: Boolean = false

    /** Only groups need these; resolved once per load rather than per drawn row. */
    private var senderNames: Map<String, String> = emptyMap()

    /** Ask-mode link taps land here; the subscription in onCreate answers them. */
    private val messageLinkClicks: io.reactivex.subjects.Subject<android.net.Uri> =
        io.reactivex.subjects.PublishSubject.create()
    private val isGroup: Boolean get() = threadKey.startsWith("group:")
    private lateinit var threadKey: String

    /** The picked file, already a data URI. Held until the message is actually sent. */
    private var pendingAttachment: String? = null
    private var pendingName: String? = null

    private val picker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> if (uri != null) attach(uri) }

    /**
     * The attachment a "Save…" is waiting on, while the system asks where to put it.
     *
     * Held rather than passed, because `ACTION_CREATE_DOCUMENT` answers through a separate
     * callback and carries nothing of ours back. Cleared as soon as it is used or abandoned.
     */
    private var pendingSave: SavedAttachment? = null

    /** An attachment on its way out of the app: what to fetch, and what to call it. */
    private data class SavedAttachment(val id: String, val filename: String, val type: String)

    /**
     * Where to put a saved attachment, asked of the system rather than decided here.
     *
     * `ACTION_CREATE_DOCUMENT` needs no storage permission and lets somebody put the file
     * where the app that will open it can find it. That is the whole point of the request
     * this answers: an epub is wanted **in a reader**, not in here.
     */
    private val saveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { destination: Uri? ->
        val pending = pendingSave
        pendingSave = null
        if (destination == null || pending == null) return@registerForActivityResult
        writeAttachmentTo(pending, destination)
    }

    /**
     * Whether the microphone is open right now.
     *
     * The recorder is a process-wide singleton shared with the MMS composer, so this says
     * only whether *this* screen started it. Asking the recorder would answer for both.
     */
    private var recording = false

    /** When the microphone opened, for the length shown and for [MIN_RECORDING_MS]. */
    private var recordingStartedAt = 0L

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Asked for because a tap asked for it, so act on the answer rather than making
        // somebody tap again for the same thing.
        if (granted) startRecording()
        else Toast.makeText(this, R.string.signal_record_needs_permission, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        binding = SignalThreadActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        threadKey = SignalConversationsActivity.threadKeyOf(intent)
        if (threadKey.isBlank()) { finish(); return }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // The toolbar holds its own title view so the rail badge can sit beside it, the way
        // the SMS thread holds its own. The stock one would draw over both.
        supportActionBar?.setDisplayShowTitleEnabled(false)
        val passed = SignalConversationsActivity.titleOf(intent)
        binding.toolbarTitle.text = passed.ifBlank { getString(R.string.signal_title) }
        // Always read the row: the title is only missing when arriving from the SMS side,
        // but which shelf the thread is on has to be known however it was opened, or the
        // archive action offers to archive something already archived.
        loadThreadState(needTitle = passed.isBlank())

        adapter = MessageAdapter()
        val lm = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.recyclerView.layoutManager = lm
        binding.recyclerView.adapter = adapter
        // As in an SMS thread: by pixels, so a bubble taller than the screen takes two pages
        // rather than being skipped.
        binding.recyclerView.turnsAPageOnSwipe()

        val results = signalRepo.getMessages(threadKey)
        messages = results
        results.addChangeListener { data, _ ->
            adapter.submit(data)
            binding.empty.setVisible(data.isEmpty())
            if (data.isNotEmpty()) binding.recyclerView.scrollToPosition(data.size - 1)
            markRead(data)
        }
        adapter.submit(results)
        binding.empty.setVisible(results.isEmpty())
        markRead(results)

        // The composer is disabled, visibly and with a reason, whenever a send would
        // fail. Sending has no offline queue: a message the user thinks they sent and
        // which never arrives is worse than being told plainly that it cannot go now.
        disposables.add(signalRepo.connectionState().subscribe { conn ->
            // Three states, not two: connected, on its way, and not going to happen. The
            // middle one used to read as the last one, on every launch.
            val blocked = when {
                conn.signalConnected -> null
                conn.connecting -> getString(R.string.signal_connecting_signal)
                else -> getString(R.string.signal_cannot_send_signal)
            }
            runOnUiThread {
                binding.cannotSend.text = blocked.orEmpty()
                binding.cannotSend.setVisible(blocked != null)
                binding.send.isEnabled = blocked == null
                binding.message.isEnabled = blocked == null
            }
        })

        // The same dialog the SMS thread shows, worded identically, because it is the same
        // question about the same kind of message.
        disposables.add(messageLinkClicks.subscribe { uri ->
            AlertDialog.Builder(this)
                .setTitle(R.string.messageLinkHandling_dialog_title)
                .setMessage(getString(R.string.messageLinkHandling_dialog_body, uri.toString()))
                .setPositiveButton(R.string.messageLinkHandling_dialog_positive) { _, _ ->
                    // Shown to the person rather than logged: they asked for this link to
                    // open, so a phone with nothing that handles it has to say so.
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW).setData(uri)) }
                        .onFailure {
                            Toast.makeText(this, R.string.signal_link_no_app, Toast.LENGTH_SHORT).show()
                        }
                }
                .setNegativeButton(R.string.messageLinkHandling_dialog_negative, null)
                .show()
        })

        binding.send.setOnClickListener { send() }
        // Hold Send to send it later. A second button would cost a sixth of the composer
        // row on a 480px screen to hold something used once a month.
        binding.send.setOnLongClickListener {
            scheduleSend()
            true
        }
        findSmsCounterpart()

        if (isGroup) {
            thread(isDaemon = true) {
                val names = runCatching { signalRepo.senderNamesFor(threadKey) }
                    .getOrDefault(emptyMap())
                if (names.isNotEmpty()) runOnUiThread {
                    senderNames = names
                    binding.recyclerView.adapter?.notifyDataSetChanged()
                }
            }
        }
        // The badge is the way across, as it has always been. The conversation is merged on
        // this screen, but the separate threads are still worth reaching -- being able to
        // see one rail on its own is the point of keeping them apart underneath.
        binding.railBadge.setOnClickListener {
            if (smsThreadId != 0L) {
                navigator.showConversation(smsThreadId)
                // Crossing rails replaces this screen rather than stacking on top of it.
                // Without this, hopping SMS -> Signal -> SMS -> Signal left four thread
                // screens on the stack and back walked all the way down through them; from a
                // conversation, back should mean the conversation list.
                finish()
            }
        }
        showRailBadge()
        binding.searchClose.setOnClickListener { closeSearch() }
        binding.searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString().orEmpty()
                adapter.filterBy(query)
                binding.searchCount.text = when {
                    query.isBlank() -> ""
                    adapter.matchCount() == 0 -> getString(R.string.signal_find_none)
                    else -> adapter.matchCount().toString()
                }
                // Newest match in view, which is where a conversation is usually read from.
                if (adapter.itemCount > 0) {
                    binding.recyclerView.scrollToPosition(adapter.itemCount - 1)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })

        binding.attach.setOnClickListener { picker.launch("*/*") }

        binding.record.setOnClickListener {
            if (recording) stopRecording() else askForMicThenRecord()
        }
        binding.pending.setOnClickListener { clearAttachment() }
    }

    private fun markRead(data: List<SignalMessage>) {
        val newest = data.maxOfOrNull { it.date } ?: return
        if (data.any { !it.outgoing && !it.read }) signalRepo.markRead(threadKey, newest)
    }

    /** Reads the picked file into a data URI; see [SignalAttachment] for why it resizes. */
    private fun attach(uri: Uri) {
        thread(isDaemon = true) {
            val type = contentResolver.getType(uri) ?: "application/octet-stream"
            val result = runCatching { SignalAttachment.dataUri(this@SignalThreadActivity, uri) }
            runOnUiThread {
                result.onSuccess { dataUri ->
                    pendingAttachment = dataUri
                    pendingName = SignalAttachment.displayName(this@SignalThreadActivity, uri) ?: type
                    binding.pending.text = getString(R.string.signal_attached, pendingName)
                    binding.pending.setVisible(true)
                }.onFailure {
                    // ⚠ Told, not swallowed, and told differently for the one cause somebody
                    // can act on: a file too large is a different instruction from a file that
                    // would not read. The composer keeps no attachment either way, so nothing
                    // is sent that the person believes was attached.
                    val msg = if (it is SignalAttachment.TooLarge) {
                        R.string.signal_attach_too_big
                    } else {
                        R.string.signal_attach_failed
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // --- attachments: getting them off this screen --------------------------------------
    //
    // Reported on the forum: pictures showed "only very small and I can't do anything with
    // them (like download them)", and a file like an epub -- sent to a Kompakt precisely
    // *because* it is a quick way to get a book onto it -- could not be got out of the app at
    // all. Both were true: an image was a capped thumbnail with no gesture on it, and anything
    // else was a line of text.
    //
    // Handed to the phone rather than solved in here. This app has no image viewer and has no
    // business growing an epub reader; the device already has both, and an attachment is
    // useful exactly when it reaches them.

    /**
     * The first attachment on this message that is actually on the phone, or null.
     *
     * ⚠ An id is the test, not the presence of an entry. Two kinds of row carry no id and
     * nothing to open: one this phone **sent** (Signal assigns an id on upload and never
     * reports it back) and one that arrived but was never fetched. Offering "save" for either
     * would be offering to write a file that does not exist.
     */
    private fun downloadableAttachment(m: SignalMessage): SavedAttachment? {
        if (m.attachments.isBlank()) return null
        val entry = runCatching { JSONArray(m.attachments) }.getOrNull()
            ?.takeIf { it.length() > 0 }?.optJSONObject(0) ?: return null
        val id = entry.optString("id")
        if (id.isBlank() || entry.optBoolean("pending")) return null
        return SavedAttachment(
            id = id,
            filename = entry.optString("filename"),
            type = entry.optString("type")
        )
    }

    /**
     * Puts an attachment somewhere another app can open it, and returns the shareable uri.
     *
     * The bytes live in this app's private store, which nothing else can read, so they are
     * copied into the cache directory the manifest's `FileProvider` is allowed to hand out.
     *
     * ⚠ The name is rebuilt rather than trusted. It arrives from whoever sent the message, so
     * it is theirs to choose: a name carrying a path separator would write outside the one
     * directory this is allowed to touch. See [safeFilename].
     *
     * @return null if the attachment is not on the phone, having said so.
     */
    private fun shareableUri(attachment: SavedAttachment): Uri? {
        val bytes = runCatching { signalRepo.loadAttachment(attachment.id) }.getOrNull()
        if (bytes == null || bytes.isEmpty()) return null
        return runCatching {
            val file = java.io.File(cacheDir, safeFilename(attachment))
            file.writeBytes(bytes)
            androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.messagesText", file
            )
        }.onFailure { Timber.w(it, "signal attachment: could not stage a copy") }.getOrNull()
    }

    /** Opens an attachment in whatever app on the phone handles it. */
    private fun openAttachment(attachment: SavedAttachment) {
        // Reading the bytes and writing the copy are both disk work, so they happen here and
        // only the hand-off goes back to the main thread.
        thread(isDaemon = true) {
            val uri = shareableUri(attachment)
            if (uri == null) {
                runOnUiThread {
                    Toast.makeText(
                        this, R.string.signal_attachment_unavailable, Toast.LENGTH_SHORT
                    ).show()
                }
                return@thread
            }
            runOnUiThread {
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, attachment.type.ifBlank { "*/*" }.lowercase())
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { startActivity(Intent.createChooser(intent, null)) }
                    .onFailure {
                        // A Kompakt has few apps on it, so "nothing can open this" is an
                        // ordinary outcome rather than a fault -- and saying so beats a
                        // crash or a tap that does nothing.
                        Toast.makeText(
                            this, R.string.signal_attachment_no_app, Toast.LENGTH_LONG
                        ).show()
                    }
            }
        }
    }

    /** Asks where to put a copy, then writes it there. */
    private fun saveAttachment(attachment: SavedAttachment) {
        pendingSave = attachment
        runCatching { saveLauncher.launch(safeFilename(attachment)) }
            .onFailure {
                pendingSave = null
                Timber.w(it, "signal attachment: no document picker")
                Toast.makeText(this, R.string.signal_attachment_no_app, Toast.LENGTH_LONG).show()
            }
    }

    /**
     * Copies the attachment into the document the person chose.
     *
     * Off the main thread, and it reports both ways: a save that quietly did not happen is
     * indistinguishable from one that did until the file is looked for and is not there.
     */
    private fun writeAttachmentTo(attachment: SavedAttachment, destination: Uri) {
        thread(isDaemon = true) {
            val bytes = runCatching { signalRepo.loadAttachment(attachment.id) }.getOrNull()
            val written = bytes != null && bytes.isNotEmpty() && runCatching {
                contentResolver.openOutputStream(destination)?.use { it.write(bytes) }
                    ?: throw java.io.IOException("nothing would open ${'$'}destination")
            }.onFailure { Timber.w(it, "signal attachment: could not save") }.isSuccess
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (written) R.string.signal_attachment_saved
                    else R.string.signal_attachment_save_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * A name safe to write into this app's cache, from one somebody else chose.
     *
     * ⚠ Anything that is not a plain name is replaced rather than cleaned up: the sender picks
     * this string, and a separator in it would put the file outside the single directory the
     * `FileProvider` is allowed to serve. The extension is kept where there is one, because it
     * is what a reader app matches on when the content type is vague.
     */
    private fun safeFilename(attachment: SavedAttachment): String {
        val proposed = attachment.filename.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = proposed.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_')
        if (cleaned.isNotBlank()) return cleaned.take(120)
        // Nothing usable was sent. The id is unique and the type gives an extension.
        val extension = android.webkit.MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(attachment.type.lowercase())
        return "signal-${'$'}{attachment.id.takeLast(12)}" + if (extension != null) ".${'$'}extension" else ""
    }

    // --- voice messages ------------------------------------------------------------------

    private fun askForMicThenRecord() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) startRecording() else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    /**
     * Opens the microphone, in the format Signal's own clients record.
     *
     * ⚠ [MediaRecorderManager.Format.SIGNAL_AAC], not the MMS default. The recorder is shared
     * with the MMS composer, whose format is AMR narrowband because that is what survives a
     * carrier transcoder -- and which arrives on Signal as a file nothing offers to play.
     */
    private fun startRecording() {
        val uri = com.wanderwildwood.kotozute.manager.MediaRecorderManager.startRecording(
            this,
            format = com.wanderwildwood.kotozute.manager.MediaRecorderManager.Format.SIGNAL_AAC
        )
        if (uri == Uri.EMPTY) {
            Toast.makeText(this, R.string.signal_record_failed, Toast.LENGTH_LONG).show()
            return
        }
        recording = true
        recordingStartedAt = System.currentTimeMillis()
        binding.pending.setText(R.string.signal_recording)
        binding.pending.setVisible(true)
    }

    /**
     * Closes the microphone and puts the recording in the composer, marked as a voice note.
     *
     * It is **not** sent here. A recording that sends itself the instant a finger lifts gives
     * nobody the chance to think better of it, and this screen already has a send button.
     */
    private fun stopRecording() {
        recording = false
        val uri = com.wanderwildwood.kotozute.manager.MediaRecorderManager.stopRecording()
        val heldFor = System.currentTimeMillis() - recordingStartedAt
        if (uri == Uri.EMPTY) {
            binding.pending.setVisible(false)
            Toast.makeText(this, R.string.signal_record_failed, Toast.LENGTH_LONG).show()
            return
        }

        // ⚠ A recording this short is usually a mis-tap, and AAC/ADTS has a further problem
        // with them: a stream stopped before the encoder has written a frame is a file of
        // zero length, which uploads and arrives as a voice note that plays nothing. Refusing
        // here costs a retap; not refusing sends silence that looks fine from this end.
        if (heldFor < MIN_RECORDING_MS) {
            com.wanderwildwood.kotozute.util.FileUtils.deleteFile(uri)
            binding.pending.setVisible(false)
            Toast.makeText(this, R.string.signal_record_too_short, Toast.LENGTH_SHORT).show()
            return
        }

        thread(isDaemon = true) {
            val result = runCatching {
                val bytes = contentResolver.openInputStream(uri).use { it!!.readBytes() }
                if (bytes.isEmpty()) throw IllegalStateException("the recording is empty")
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                // The marker rides inside the data URI so it cannot be separated from the
                // bytes it describes on the way to the sender. See [VoiceNotes].
                com.wanderwildwood.kotozute.signal.VoiceNotes.mark(
                    "data:${com.wanderwildwood.kotozute.signal.VoiceNotes.CONTENT_TYPE};base64,$encoded"
                )
            }
            // The cache copy has served its purpose either way; the bytes are in memory now
            // and the housekeeping sweep should not be the only thing that ever removes it.
            com.wanderwildwood.kotozute.util.FileUtils.deleteFile(uri)
            runOnUiThread {
                result.onSuccess { dataUri ->
                    pendingAttachment = dataUri
                    pendingName = getString(R.string.signal_voice_message)
                    binding.pending.text = getString(
                        R.string.signal_recorded,
                        spokenLength(heldFor)
                    )
                    binding.pending.setVisible(true)
                }.onFailure {
                    binding.pending.setVisible(false)
                    Toast.makeText(this, R.string.signal_record_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Which voice note is playing, so a second tap on the same row stops it and a tap on a
     * different one replaces it.
     */
    private var playingAttachmentId: String? = null

    /**
     * Plays a received voice note, or stops it if it is the one already playing.
     *
     * ⚠ Written to a cache file rather than played from memory. `MediaPlayer` takes a path,
     * a URI or a file descriptor and none of those is a byte array; the alternative is a
     * local socket, which is a lot of machinery for a file measured in tens of kilobytes.
     * The copy is deleted as soon as playback ends or is replaced.
     */
    private fun togglePlayback(id: String, onState: (Boolean) -> Unit) {
        if (playingAttachmentId == id) {
            stopPlayback()
            onState(false)
            return
        }
        stopPlayback()

        thread(isDaemon = true) {
            val bytes = signalRepo.loadAttachment(id)
            if (bytes == null || bytes.isEmpty()) {
                runOnUiThread {
                    onState(false)
                    Toast.makeText(this, R.string.signal_voice_message_failed, Toast.LENGTH_SHORT).show()
                }
                return@thread
            }
            val file = java.io.File(cacheDir, "voice-$id.aac")
            val ok = runCatching { file.writeBytes(bytes) }.isSuccess
            runOnUiThread {
                if (!ok) {
                    onState(false)
                    Toast.makeText(this, R.string.signal_voice_message_failed, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val started = runCatching {
                    QkMediaPlayer.reset()
                    QkMediaPlayer.setDataSource(this, Uri.fromFile(file))
                    QkMediaPlayer.prepare()
                    QkMediaPlayer.setOnCompletionListener {
                        // The row has to be told, or it keeps offering to stop something
                        // that already finished.
                        playingAttachmentId = null
                        file.delete()
                        runOnUiThread { onState(false) }
                    }
                    QkMediaPlayer.start()
                }.isSuccess

                if (started) {
                    playingAttachmentId = id
                    playingFile = file
                    onState(true)
                } else {
                    file.delete()
                    onState(false)
                    Toast.makeText(this, R.string.signal_voice_message_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** The cache copy currently being played, so it can be removed when playback stops. */
    private var playingFile: java.io.File? = null

    private fun stopPlayback() {
        if (playingAttachmentId == null) return
        playingAttachmentId = null
        runCatching { QkMediaPlayer.reset() }
        playingFile?.delete()
        playingFile = null
    }

    /**
     * How long a recording ran, as m:ss.
     *
     * Its own function rather than a `DateFormatter` entry: everything there formats a point
     * in time against the reader's locale and calendar, and a duration is neither.
     */
    private fun spokenLength(millis: Long): String {
        val seconds = (millis / 1000).coerceAtLeast(0)
        return String.format(java.util.Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
    }

    private fun clearAttachment() {
        pendingAttachment = null
        pendingName = null
        binding.pending.setVisible(false)
    }

    /**
     * Put what is in the box in the scheduled list instead of sending it now.
     *
     * Text only, and said so rather than silently dropping an attachment: an attachment is
     * held as a data URI, and keeping a photo's worth of base64 in the database until
     * Tuesday is not worth what it buys.
     */
    private fun scheduleSend() {
        val body = binding.message.text?.toString().orEmpty().trim()
        if (body.isEmpty()) {
            Toast.makeText(this, R.string.signal_schedule_needs_text, Toast.LENGTH_SHORT).show()
            return
        }
        if (pendingAttachment != null) {
            Toast.makeText(this, R.string.signal_schedule_no_attachments, Toast.LENGTH_LONG).show()
            return
        }

        val calendar = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            TimePickerDialog(this, { _, hour, minute ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, day)
                calendar.set(Calendar.HOUR_OF_DAY, hour)
                calendar.set(Calendar.MINUTE, minute)
                calendar.set(Calendar.SECOND, 0)
                commitSchedule(calendar.timeInMillis, body)
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE),
                android.text.format.DateFormat.is24HourFormat(this)).show()
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun commitSchedule(at: Long, body: String) {
        if (at <= System.currentTimeMillis()) {
            Toast.makeText(this, R.string.signal_schedule_in_the_past, Toast.LENGTH_SHORT).show()
            return
        }
        thread(isDaemon = true) {
            val failure = runCatching {
                // The thread's name rides along in recipients, which Signal does not
                // otherwise use. Without it the scheduled list would have a uuid where a
                // name should be, and no way to resolve one -- the row is read long after
                // this screen is gone.
                scheduledMessageRepo.saveScheduledMessage(
                    date = at,
                    subId = -1,
                    recipients = listOf(binding.toolbarTitle.text.toString()),
                    sendAsGroup = false,
                    body = body,
                    attachments = emptyList(),
                    conversationId = 0,
                    signalThreadKey = threadKey
                )
                updateScheduledMessageAlarms.execute(Unit)
            }.exceptionOrNull()
            runOnUiThread {
                if (failure == null) {
                    binding.message.setText("")
                    Toast.makeText(this, R.string.signal_scheduled, Toast.LENGTH_SHORT).show()
                } else {
                    Timber.w(failure, "signal: schedule")
                    Toast.makeText(this, R.string.signal_schedule_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun send() {
        val body = binding.message.text?.toString().orEmpty().trim()
        val attachment = pendingAttachment
        if (body.isEmpty() && attachment == null) return
        binding.send.isEnabled = false
        thread(isDaemon = true) {
            val result = runCatching {
                signalRepo.send(threadKey, body, listOfNotNull(attachment))
            }
            runOnUiThread {
                binding.send.isEnabled = true
                result
                    .onSuccess {
                        binding.message.setText("")
                        clearAttachment()
                    }
                    .onFailure { failure ->
                        // The message stays in the box either way, so nothing typed is lost.
                        if (failure is SafetyNumberChanged) {
                            offerSafetyNumberChoice(failure, body, attachment)
                        } else if (failure is com.wanderwildwood.kotozute.repository.SentButNotFiled) {
                            // ⚠ Not a failed send, and the only branch here that must not read
                            // like one. The composer still holds the text, so the obvious next
                            // move is to press send again -- and it has already gone. Cleared
                            // for the same reason a successful send clears it.
                            binding.message.setText("")
                            clearAttachment()
                            Toast.makeText(
                                this,
                                getString(R.string.signal_sent_but_not_filed),
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(
                                this,
                                getString(R.string.signal_send_failed, failure.message.orEmpty()),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.signal_thread, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        menu?.findItem(R.id.archiveSignal)?.setTitle(
            if (isArchived) R.string.signal_unarchive else R.string.signal_archive
        )
        menu?.findItem(R.id.signalPin)?.setTitle(
            if (isPinned) R.string.main_menu_unpin else R.string.main_menu_pin
        )
        menu?.findItem(R.id.signalMute)?.setTitle(
            if (isMuted) R.string.signal_unmute else R.string.signal_mute
        )
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.signalInfo -> {
            startActivity(SignalThreadInfoActivity.intentFor(this, threadKey))
            true
        }

        R.id.signalFind -> {
            binding.searchBar.setVisible(true)
            binding.searchField.requestFocus()
            true
        }

        R.id.signalPin -> {
            isPinned = !isPinned
            signalRepo.setPinned(threadKey, isPinned)
            invalidateOptionsMenu()
            true
        }

        R.id.signalMute -> {
            isMuted = !isMuted
            signalRepo.setMuted(threadKey, isMuted)
            invalidateOptionsMenu()
            if (isMuted) {
                Toast.makeText(this, R.string.signal_muted_toast, Toast.LENGTH_SHORT).show()
            }
            true
        }

        // Leaving the thread is part of it: marked unread and then left on screen, the
        // read-on-view below would undo it before you got anywhere.
        R.id.signalMarkUnread -> {
            signalRepo.markUnread(threadKey)
            finish()
            true
        }

        R.id.archiveSignal -> {
            val nowArchived = !isArchived
            signalRepo.setArchived(threadKey, nowArchived)
            // Both halves, because the inbox shows one row for both. Archiving only the
            // Signal side took that row away and let the text conversation spring back as a
            // row of its own -- so archiving a person made them reappear.
            linkedConversationId?.takeIf { it != 0L }?.let { id ->
                if (nowArchived) markTextArchived.execute(listOf(id))
                else markTextUnarchived.execute(listOf(id))
            }
            if (nowArchived) {
                Toast.makeText(this, R.string.signal_archived_toast, Toast.LENGTH_SHORT).show()
            }
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /** What the menu needs to know about this thread; read once, off the looper. */
    private data class ThreadState(
        val archived: Boolean,
        val pinned: Boolean,
        val muted: Boolean,
        val name: String
    )

    private fun loadThreadState(needTitle: Boolean) {
        thread(isDaemon = true) {
            val state = runCatching {
                io.realm.Realm.getDefaultInstance().use { realm ->
                    realm.where(com.wanderwildwood.kotozute.model.SignalThread::class.java)
                        .equalTo("threadKey", threadKey)
                        .findFirst()
                        ?.let {
                            ThreadState(
                                archived = it.archived,
                                pinned = it.pinned,
                                muted = it.muted,
                                name = com.wanderwildwood.kotozute.signal.SignalName.of(
                                    name = it.title,
                                    number = it.counterpartNumber,
                                    serviceId = it.threadKey.substringAfter(":")
                                )
                            )
                        }
                }
            }.getOrNull() ?: return@thread

            runOnUiThread {
                isArchived = state.archived
                isPinned = state.pinned
                isMuted = state.muted
                invalidateOptionsMenu()
                if (needTitle && state.name.isNotBlank()) binding.toolbarTitle.text = state.name
            }
        }
    }

    /**
     * The badge always names the rail, because on a phone where one person can hold a thread
     * on each, that is worth saying. The arrow is only there when there is somewhere to go:
     * a badge that looks tappable and does nothing is worse than a plain label.
     */
    private fun showRailBadge() {
        val label = getString(R.string.signal_rail_label)
        binding.railBadge.text = if (smsThreadId != 0L) "$label $RAIL_SWITCH_ARROW" else label
        binding.railBadge.isClickable = smsThreadId != 0L
        binding.railBadge.contentDescription =
            if (smsThreadId != 0L) getString(R.string.signal_switch_to_sms) else label
    }

    /**
     * Offers the decision where the person already is, rather than leaving them a sentence.
     *
     * ⚠ Signal has **no standalone "accept this key?"** anywhere. A send blocked by a changed
     * safety number puts the choice in front of the send itself -- "Send anyway", which trusts
     * the new key and resends in one action, alongside a way to check the number first. The
     * thing they were already trying to do is what carries the decision.
     *
     * This app used to fail the send with a sentence and leave them to work out unaided that a
     * row on another screen was the remedy. Drawn as this app draws a choice of actions -- the
     * same picker the message menu uses -- rather than as Signal's bottom sheet, and the
     * consequential one arms rather than asking again, per STYLE.md.
     *
     * Checking comes first and sending anyway last: the safer path is the one a thumb finds
     * without aiming, and a consequential action goes last.
     */
    private fun offerSafetyNumberChoice(
        failure: SafetyNumberChanged,
        body: String,
        attachment: String?,
        armed: Boolean = false
    ) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += getString(R.string.signal_safety_verify) to {
            startActivity(SignalThreadInfoActivity.intentFor(this, threadKey))
        }
        actions += if (armed) {
            getString(R.string.signal_safety_send_anyway_armed) to {
                acceptAndResend(body, attachment)
            }
        } else {
            getString(R.string.signal_safety_send_anyway) to {
                offerSafetyNumberChoice(failure, body, attachment, armed = true)
            }
        }

        val dialog = AlertDialog.Builder(this)
            // Two strings rather than a placeholder filled with "their": "Their's safety
            // number changed" is what one string and a fallback word produces.
            .setTitle(
                failure.name
                    ?.let { getString(R.string.signal_safety_changed_title, it) }
                    ?: getString(R.string.signal_safety_changed_title_unknown)
            )
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()

        if (armed) {
            val decor = dialog.window?.decorView
            val disarm = Runnable {
                if (!isFinishing && dialog.isShowing) {
                    dialog.dismiss()
                    offerSafetyNumberChoice(failure, body, attachment, armed = false)
                }
            }
            decor?.postDelayed(disarm, ARM_TIMEOUT_MS)
            dialog.setOnDismissListener { decor?.removeCallbacks(disarm) }
        }
    }

    /**
     * Trusts the new key and sends the message that was refused, in one action.
     *
     * Upstream's `trustAndVerify` then resend. Accepting archives the sessions built on the old
     * key -- see `SignalStore.acceptIdentity` -- so the resend negotiates a fresh one rather
     * than going out over a ratchet the other end has moved off.
     */
    private fun acceptAndResend(body: String, attachment: String?) {
        binding.send.isEnabled = false
        thread(isDaemon = true) {
            val accepted = runCatching { signalRepo.acceptIdentity(threadKey) }.getOrDefault(false)
            val result = if (accepted) {
                runCatching { signalRepo.send(threadKey, body, listOfNotNull(attachment)) }
            } else {
                Result.failure(IllegalStateException(getString(R.string.signal_safety_accept_failed)))
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                binding.send.isEnabled = true
                result
                    .onSuccess {
                        binding.message.setText("")
                        clearAttachment()
                    }
                    .onFailure {
                        // ⚠ The composer is deliberately **not** cleared here -- clearing is
                        // in onSuccess alone -- so a failed send leaves the text where they
                        // typed it. The message names the reason the send path gave, because
                        // "it failed" and "they have left Signal" need different responses.
                        Toast.makeText(
                            this,
                            getString(R.string.signal_send_failed, it.message.orEmpty()),
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
        }
    }

    /**
     * Copy or share one message. A dialog rather than a selection mode: selection earns its
     * complexity when you act on many messages at once, and here there is nothing yet that
     * takes more than one.
     */
    private fun showMessageActions(
        body: String,
        messageId: String,
        mine: String,
        outgoing: Boolean,
        sentAt: Long,
        /** What this message carries, where it is on the phone. Null when there is nothing. */
        attachment: SavedAttachment? = null,
        /** Whether "take back" is already armed; see below. */
        armed: Boolean = false
    ) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += getString(R.string.signal_react) to { askForReaction(messageId, mine) }
        // Only where there is something to act on; see [downloadableAttachment].
        attachment?.let { saved ->
            actions += getString(R.string.signal_attachment_open) to { openAttachment(saved) }
            actions += getString(R.string.signal_attachment_save) to { saveAttachment(saved) }
        }
        if (mine.isNotEmpty()) {
            actions += getString(R.string.signal_reaction_remove_mine, mine) to {
                sendReaction(messageId, mine, remove = true)
            }
        }
        if (body.isNotBlank()) {
            actions += listOf(
                getString(R.string.signal_message_copy) to {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Signal message", body))
                    Toast.makeText(this, R.string.signal_message_copied, Toast.LENGTH_SHORT).show()
                },
                getString(R.string.signal_message_share) to {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, body)
                            },
                            getString(R.string.signal_message_share)
                        )
                    )
                }
            )
        }

        // Last, because it is the destructive one, and offered only on our own messages and
        // only while Signal would still accept it -- offering it on somebody else's message,
        // or on one too old to withdraw, is offering something that can only end in an
        // apology.
        //
        // It arms rather than opening a dialog to ask. A second dialog stacked on this one
        // would be two full-panel repaints to ask one question; the row asks in its own face
        // instead, and disarms itself so a stray tap leaves no live trigger behind.
        if (SignalRepository.canWithdraw(outgoing, sentAt)) {
            actions += if (armed) {
                getString(R.string.signal_withdraw_armed) to { withdraw(messageId) }
            } else {
                getString(R.string.signal_withdraw) to {
                    showMessageActions(body, messageId, mine, outgoing, sentAt, attachment, armed = true)
                }
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()

        if (armed) {
            val decor = dialog.window?.decorView
            val disarm = Runnable {
                if (!isFinishing && dialog.isShowing) {
                    dialog.dismiss()
                    showMessageActions(body, messageId, mine, outgoing, sentAt, attachment, armed = false)
                }
            }
            decor?.postDelayed(disarm, ARM_TIMEOUT_MS)
            // Choosing anything else, or backing out, takes the trigger with it.
            dialog.setOnDismissListener { decor?.removeCallbacks(disarm) }
        }
    }

    /**
     * The short row of emoji Signal itself offers, and a way to take one back.
     *
     * Six, not a picker. A reaction is a quick thing and a grid of a thousand glyphs on a
     * 480px e-ink screen is not quick -- the phone's own emoji panel exists for the
     * composer, where somebody is actually writing.
     */
    private fun askForReaction(messageId: String, mine: String) {
        val choices = listOf("\u2764\ufe0f", "\uD83D\uDC4D", "\uD83D\uDC4E", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDE22")

        // A row, not a list. Six list items would be six full-width rows on a 480px screen
        // to hold six glyphs, and the eye has to travel the height of the display to read a
        // choice that fits on one line.
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(16), dp(8), dp(16))
        }
        val dialog = AlertDialog.Builder(this).setView(row).show()
        choices.forEach { emoji ->
            val cell = com.wanderwildwood.kotozute.common.widget.QkTextView(this).apply {
                text = emoji
                textSize = 26f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                // The one already given is ringed, and tapping it takes it back -- which is
                // what tapping it again does in Signal itself.
                if (emoji == mine) {
                    setBackgroundResource(R.drawable.rounded_rectangle_outline_4dp)
                }
                setOnClickListener {
                    dialog.dismiss()
                    sendReaction(messageId, emoji, remove = emoji == mine)
                }
            }
            row.addView(
                cell,
                android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
    }

    /**
     * Takes a message back, having been asked twice.
     *
     * Signal asks once and remembers the answer for ever. This asks every time: the gesture
     * sits one long-press from "Copy text" on a 480px screen, it cannot be undone, and it
     * reaches other people's phones.
     */
    private fun withdraw(messageId: String) {
        thread(isDaemon = true) {
            val failure = runCatching { signalRepo.withdraw(messageId) }.exceptionOrNull()
            if (failure != null) {
                Timber.w(failure, "signal: withdrawal")
                runOnUiThread {
                    Toast.makeText(
                        this, getString(R.string.signal_withdraw_failed), Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun sendReaction(messageId: String, emoji: String, remove: Boolean) {
        thread(isDaemon = true) {
            val failure = runCatching { signalRepo.react(messageId, emoji, remove) }
                .exceptionOrNull()
            if (failure != null) {
                Timber.w(failure, "signal: reaction")
                runOnUiThread {
                    Toast.makeText(
                        this, getString(R.string.signal_reaction_failed), Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * Which emoji this account already put on a message, so "remove" knows what to take off
     * -- Signal removes a specific reaction, not whatever happens to be there.
     *
     * Ours is recorded against "me" when the echo of it comes back, so this needs neither a
     * network call nor a copy of the account's own uuid.
     */
    private fun myReaction(m: SignalMessage): String = runCatching {
        val arr = JSONArray(m.reactions.ifBlank { "[]" })
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            if (e.optString("who") == "me") return@runCatching e.optString("emoji")
        }
        ""
    }.getOrDefault("")

    private fun findSmsCounterpart() {
        thread(isDaemon = true) {
            // A link made by hand wins over any matching, and is checked first. It exists
            // for the pairs matching cannot see -- a contact whose Signal shares no number
            // at all -- and a link set from the browser has to hold here too, or the same
            // two conversations are joined in one place and separate in the other.
            runCatching { signalRepo.linkedConversationId(threadKey) }.getOrNull()?.let { linked ->
                runOnUiThread {
                    smsThreadId = linked
                    showRailBadge()
                    invalidateOptionsMenu()
                    linkedConversationId = linked
                }
                return@thread
            }

            // Otherwise the number, which lives on the thread row -- read it rather than
            // guess it from the key.
            val n = runCatching {
                io.realm.Realm.getDefaultInstance().use { realm ->
                    realm.where(com.wanderwildwood.kotozute.model.SignalThread::class.java)
                        .equalTo("threadKey", threadKey)
                        .findFirst()?.counterpartNumber.orEmpty()
                }
            }.getOrDefault("")
            if (n.isBlank()) return@thread
            // This number, and only this number.
            //
            // It used to try every number on the same address-book card, so that someone
            // whose Signal was still on an old number crossed to their live SMS thread.
            // That is gone on purpose, and the same change is made on the other side in
            // findThreadForNumber: two rails reached on two different numbers are two
            // conversations, because keeping them apart is a thing a person may want and
            // the app cannot tell that intent from an oversight.
            val id = runCatching {
                conversationRepo.getConversation(listOf(n))?.id ?: 0L
            }.getOrDefault(0L)
            if (id != 0L) runOnUiThread {
                smsThreadId = id
                showRailBadge()
                invalidateOptionsMenu()
                linkedConversationId = id
            }
        }
    }

    override fun onResume() {
        super.onResume()
        visibleThreadKey = threadKey
        // Whatever was announced about this conversation is answered by reading it.
        notifications.cancel(threadKey)
    }

    override fun onPause() {
        if (visibleThreadKey == threadKey) visibleThreadKey = null
        // ⚠ The microphone does not close itself. The recorder is a process-wide singleton,
        // so a screen left while recording would hold the mic open behind whatever comes
        // next -- including the MMS composer, which would then find it already in use. The
        // recording is kept rather than discarded: it is in the composer, where an unsent
        // draft belongs.
        if (recording) stopRecording()
        stopPlayback()
        super.onPause()
    }

    /** Leave the filtered view before leaving the screen, so back does the nearer thing. */
    private fun closeSearch() {
        binding.searchField.setText("")
        binding.searchBar.setVisible(false)
        binding.searchCount.text = ""
        adapter.filterBy("")
    }

    override fun onBackPressed() {
        if (binding.searchBar.visibility == android.view.View.VISIBLE) {
            closeSearch()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (binding.searchBar.visibility == android.view.View.VISIBLE) {
            closeSearch()
            return true
        }
        finish()
        return true
    }

    override fun onDestroy() {
        messages?.removeAllChangeListeners()
        disposables.clear()
        stopPlayback()
        super.onDestroy()
    }

    companion object {
        /** Plain ASCII on purpose: the Kompakt's font has no glyph for the nicer arrows. */
        private const val RAIL_SWITCH_ARROW = ">"

        /** How long an armed destructive row stays armed, as everywhere else in the app. */
        private const val ARM_TIMEOUT_MS = 4000L

        /**
         * The shortest recording worth sending.
         *
         * Below this a tap-to-start immediately followed by tap-to-stop has usually not let
         * the AAC encoder write a single frame, and what comes out is a zero-length file that
         * uploads happily and plays nothing on the other end.
         */
        private const val MIN_RECORDING_MS = 700L


        /**
         * Which conversation is on screen, so a notification is not raised about a message
         * the user is watching arrive.
         */
        @Volatile private var visibleThreadKey: String? = null

        fun isVisible(threadKey: String): Boolean = visibleThreadKey == threadKey
    }

    private inner class MessageAdapter : RecyclerView.Adapter<MessageHolder>() {
        override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
            super.onAttachedToRecyclerView(recyclerView)
            recyclerView.stopAnimatingItems()
        }

        /** Everything in the thread. [items] is what is on screen, which may be a subset. */
        private var all: List<SignalMessage> = emptyList()
        private var items: List<SignalMessage> = emptyList()
        private var filter: String = ""

        fun submit(data: List<SignalMessage>) {
            all = data.toList()
            applyFilter()
        }

        /** Narrow to the messages containing [query]; empty restores the whole thread. */
        fun filterBy(query: String) {
            filter = query.trim()
            applyFilter()
        }

        fun matchCount(): Int = if (filter.isEmpty()) 0 else items.size

        private fun applyFilter() {
            items = if (filter.isEmpty()) all
            else all.filter { it.body.contains(filter, ignoreCase = true) }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            MessageHolder(
                SignalMessageListItemBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )

        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: MessageHolder, position: Int) =
            holder.bind(
                items[position],
                items.getOrNull(position - 1),
                items.getOrNull(position + 1)
            )
    }

    /**
     * Two messages belong to the same run when the same person sent them close together --
     * the SMS thread's rule, and the same ten minutes, so a conversation that crosses the
     * rails groups the same way on both sides.
     */
    private fun canGroup(m: SignalMessage, other: SignalMessage?): Boolean {
        if (other == null) return false
        if (m.outgoing != other.outgoing) return false
        if (m.senderUuid != other.senderUuid) return false
        return TimeUnit.MILLISECONDS.toMinutes(abs(m.date - other.date)) <
            BubbleUtils.TIMESTAMP_THRESHOLD
    }

    private inner class MessageHolder(
        private val b: SignalMessageListItemBinding
    ) : RecyclerView.ViewHolder(b.root) {
        fun bind(m: SignalMessage, previous: SignalMessage?, next: SignalMessage?) {
            // A view-once message has no body and no attachment on purpose -- the picture
            // is gone, which is the whole promise. The row is kept so the thread does not
            // have a silent hole in it; drawn as an empty bubble it was the hole anyway, and
            // indistinguishable from a rendering fault.
            val text = if (m.body.isEmpty() && m.viewOnce) {
                getString(R.string.signal_view_once_received)
            } else {
                m.body
            }
            // Links, on the same terms as the SMS thread: blocked, asked about, or opened,
            // whichever the one preference says. A Signal message is likelier than a text to
            // carry a link worth following, and until now it was something to retype.
            b.body.text = MessageLinks.apply(b.body, text, prefs, messageLinkClicks)
            b.body.setVisible(text.isNotEmpty())

            // Emoji on their own are drawn large and without a bubble on the SMS side. This
            // thread hardcoded emojiOnly = false, so the same message arriving over Signal
            // came out as small glyphs in a box. Same rule, same look, either rail.
            val emojiOnly = text.isEmojiOnly()
            textViewStyler.setTextSize(
                b.body,
                if (emojiOnly) TextViewStyler.SIZE_EMOJI else TextViewStyler.SIZE_PRIMARY
            )
            bindQuote(m)
            bindReactions(m)

            b.timestamp.text = dateFormatter.getMessageTimestamp(m.date)
            // One timestamp above a run, not one per line. Anything less than the grouping
            // threshold since the last message is the same moment as far as reading goes.
            b.timestamp.setVisible(
                TimeUnit.MILLISECONDS.toMinutes(m.date - (previous?.date ?: 0L)) >=
                    BubbleUtils.TIMESTAMP_THRESHOLD
            )
            // Who sent it only needs saying in a group. In a one-to-one thread the
            // title already says, and labelling every incoming line with the same phone
            // number is noise on a small screen.
            val label = when {
                !isGroup -> ""
                m.outgoing -> getString(R.string.signal_you)
                else -> senderNames[m.senderUuid]
                    ?: m.senderNumber.ifBlank { m.senderUuid.take(8) }
            }
            b.sender.text = label
            b.sender.setVisible(label.isNotEmpty())

            // Which side a message sits on is what tells the two apart once the labels
            // are gone. Without this, dropping the "You:" prefix left a one-to-one
            // thread where both halves of the conversation look identical.
            // Delivery, on our own messages only: a receipt is something other people send
            // about ours. Nothing is shown until something is known -- an empty state would
            // otherwise read as "not delivered", which is a different and alarming claim.
            b.status.setVisible(m.outgoing && (m.readAt > 0 || m.deliveredAt > 0))
            if (m.outgoing) {
                b.status.setText(
                    if (m.readAt > 0) R.string.signal_message_read
                    else R.string.signal_message_delivered
                )
            }

            val side = if (m.outgoing) Gravity.END else Gravity.START
            (b.root as? android.widget.LinearLayout)?.let { root ->
                // The timestamp stays centred whichever side the message is on, so only the
                // children below it follow the sender.
                listOf(b.sender, b.image, b.attachment, b.quote, b.body, b.reactions, b.status).forEach { child ->
                    (child.layoutParams as? android.widget.LinearLayout.LayoutParams)
                        ?.let { lp -> lp.gravity = side; child.layoutParams = lp }
                }
                root.gravity = Gravity.START
            }
            b.body.textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START

            // The outlined bubble, and the same first/middle/last/only shapes the SMS thread
            // uses, so a run of messages draws as one form rather than a stack of pills.
            b.body.setBackgroundResource(
                BubbleUtils.getBubble(
                    emojiOnly = emojiOnly,
                    canGroupWithPrevious = canGroup(m, previous),
                    canGroupWithNext = canGroup(m, next),
                    isMe = m.outgoing
                )
            )
            // A gap after the last message of a run, none inside one -- the grouping is the
            // bubble shape plus this, exactly as on the SMS side.
            b.root.setPadding(
                b.root.paddingLeft,
                b.root.paddingTop,
                b.root.paddingRight,
                if (canGroup(m, next)) 0 else 16.dpToPx(this@SignalThreadActivity)
            )

            // A message you could read and not copy. The SMS side has had a selection mode
            // since it was QKSMS; this is the small version of it -- the things anyone
            // actually reaches for, on the gesture they will already try.
            //
            // The id and the reactions are read now rather than off the holder later: a
            // holder is recycled onto another message, and the menu would then act on
            // whichever one had scrolled into its place.
            val messageId = m.id
            val mine = myReaction(m)
            val outgoing = m.outgoing
            val sentAt = m.date
            val saved = downloadableAttachment(m)
            val listener = android.view.View.OnLongClickListener {
                showMessageActions(m.body, messageId, mine, outgoing, sentAt, saved)
                true
            }
            b.body.setOnLongClickListener(listener)
            // An attachment with no caption is still a message, and still something to react
            // to. Without this it was the one kind that answered no gesture at all.
            b.image.setOnLongClickListener(listener)
            b.attachment.setOnLongClickListener(listener)

            bindAttachment(m)

            // A tap opens it. The picture on screen is a thumbnail sized for a message list,
            // which is the "only very small" in the report -- the full copy is what gets
            // handed to a viewer here. A voice note keeps its own tap, which plays it.
            val downloadable = downloadableAttachment(m)
            if (downloadable != null) {
                b.image.setOnClickListener { openAttachment(downloadable) }
                if (!b.attachment.hasOnClickListeners()) {
                    b.attachment.setOnClickListener { openAttachment(downloadable) }
                }
            } else {
                b.image.setOnClickListener(null)
            }
        }

        /**
         * What this message is replying to.
         *
         * Signal identifies a quote by the timestamp of what it answers, so the original is
         * found by date within this thread -- two messages sharing a millisecond in one
         * conversation is not a case worth carrying an author column for.
         *
         * A thread starts empty and fills from the day this phone was linked, so the quoted
         * message is often simply not here. That says so rather than showing nothing, because
         * a reply with no visible antecedent is the confusion this is meant to remove.
         */
        private fun bindQuote(m: SignalMessage) {
            if (m.quoteTs == 0L) {
                b.quote.setVisible(false)
                return
            }
            val original = messages?.firstOrNull { it.date == m.quoteTs }
            b.quote.text = when {
                original == null -> getString(R.string.signal_quote_missing)
                else -> {
                    // senderNames is only filled for groups -- in a one-to-one thread the
                    // name is already at the top of the screen. Without this fallback the
                    // quote line named the other person by a slice of their uuid.
                    val who = when {
                        original.outgoing -> getString(R.string.signal_quote_you)
                        else -> senderNames[original.senderUuid]
                            ?: binding.toolbarTitle.text?.toString()?.takeIf { it.isNotBlank() }
                            ?: original.senderNumber.ifBlank { original.senderUuid.take(8) }
                    }
                    val snippet = original.body.replace("\n", " ").trim().ifEmpty {
                        getString(R.string.signal_quote_no_text)
                    }
                    getString(R.string.signal_quote, who, snippet)
                }
            }
            b.quote.setVisible(true)
        }

        /**
         * Reactions others have put on this message.
         *
         * Counted per emoji and shown most-used first, the same reading the SMS thread
         * gives them -- but all of them rather than only the top one, because a Signal
         * message can genuinely carry several and there is room on a line of its own.
         */
        private fun bindReactions(m: SignalMessage) {
            if (m.reactions.isBlank()) {
                b.reactions.setVisible(false)
                return
            }
            val counts = LinkedHashMap<String, Int>()
            runCatching { JSONArray(m.reactions) }.getOrNull()?.let { arr ->
                for (i in 0 until arr.length()) {
                    val emoji = arr.optJSONObject(i)?.optString("emoji").orEmpty()
                    if (emoji.isNotEmpty()) counts[emoji] = (counts[emoji] ?: 0) + 1
                }
            }
            if (counts.isEmpty()) {
                b.reactions.setVisible(false)
                return
            }
            b.reactions.text = counts.entries
                .sortedByDescending { it.value }
                // A non-breaking space, so the count cannot wrap away from its emoji.
                .joinToString("  ") { (emoji, n) -> if (n == 1) emoji else "$emoji\u00a0$n" }
            b.reactions.setVisible(true)
        }

        private fun bindAttachment(m: SignalMessage) {
            b.image.setVisible(false)
            b.attachment.setVisible(false)
            b.image.setImageDrawable(null)
            if (m.attachments.isBlank()) return

            val first = runCatching { JSONArray(m.attachments) }
                .getOrNull()
                ?.takeIf { it.length() > 0 }
                ?.optJSONObject(0) ?: return
            val id = first.optString("id")
            // ⚠ `type`, not `contentType`. Every writer of this array uses `type` --
            // SignalReceiver for what arrives, outgoingAttachmentsJson for what is sent, and
            // SignalHistoryImporter for what is brought in -- and every reader asked for
            // `contentType`, which nothing writes. So the type was always blank, the
            // image branch below was never taken, and an incoming photo was drawn as
            // "Attachment: signal-2026-09-13-103410.jpeg" instead of as the photo.
            //
            // The exporter had it right all along: it reads `type` here and writes
            // `contentType` into the export format, which is where that name belongs.
            val type = first.optString("type")

            // Our own sent attachments carry no id: Signal assigns one on upload and does
            // not report it back. There is nothing to fetch, but the sender should still
            // see that the message carried something.
            if (id.isBlank()) {
                // ⚠ Named before it is described. Our own sent attachments carry no id --
                // Signal assigns one on upload and does not report it back -- so this branch
                // is every message this phone has sent, and it ran *before* the voice-note
                // check below. A recording the sender had just made read back to them as
                // "Attachment (audio/aac)" while the recipient saw a voice message.
                //
                // There is still nothing to play: no id means nothing to fetch. But saying
                // what it is costs nothing and is the difference between a thread that
                // reflects what was sent and one that does not.
                b.attachment.text = if (isVoiceNote(first, type)) {
                    getString(R.string.signal_voice_message)
                } else {
                    getString(
                        R.string.signal_attachment_other,
                        type.ifBlank { getString(R.string.signal_attachment_image) }
                    )
                }
                b.attachment.setVisible(true)
                return
            }

            // A voice note is a message, not a file. `voice` is the flag the sender set;
            // the type check is the fallback for anything filed before that flag was read,
            // and for a client whose voice notes are not AAC.
            //
            // ⚠ Only when there is an id to fetch. Our own sent recordings have none -- the
            // branch above has already returned for those -- so this never offers a play
            // button for something that cannot be played.
            if (isVoiceNote(first, type)) {
                bindVoiceNote(id, first.optBoolean("pending"))
                return
            }

            if (!type.startsWith("image/")) {
                b.attachment.text = getString(
                    R.string.signal_attachment_other,
                    first.optString("filename").ifBlank { type.ifBlank { id } }
                )
                b.attachment.setVisible(true)
                return
            }

            imageCache.get(id)?.let {
                b.image.setImageBitmap(it)
                b.image.setVisible(true)
                return
            }

            // Tagged so a recycled holder that has moved on does not get someone
            // else's picture when this comes back.
            b.image.tag = id
            b.attachment.text = getString(R.string.signal_attachment_image)
            b.attachment.setVisible(true)

            // One fetch per picture, however many times it is bound.
            //
            // Every bind used to start its own thread. The Find bar calls
            // notifyDataSetChanged() on every keystroke and the Realm listener calls it on
            // every change, so typing six characters in a thread with three pictures on
            // screen started eighteen threads, each doing a full pinned-TLS handshake and
            // downloading the same bytes again, each holding a decoded bitmap. Scrolling
            // did the same, and with an eight-entry cache scrolling back up refetched
            // everything. On the Kompakt that is thread and heap growth driven by typing.
            if (!inFlight.add(id)) return

            thread(isDaemon = true) {
                val bytes = signalRepo.loadAttachment(id)
                // Bounded to the width it is drawn at. See [SignalAttachment.decodeBounded]:
                // decoding a phone photo unsampled costs tens of megabytes for a thumbnail a
                // few hundred pixels wide, and the cache below would hold several at once.
                val bmp = bytes?.let {
                    SignalAttachment.decodeBounded(it, b.image.width.takeIf { w -> w > 0 }
                        ?: SignalAttachment.THUMBNAIL_EDGE)
                }
                if (bmp != null) imageCache.put(id, bmp)
                runOnUiThread {
                    inFlight.remove(id)
                    // The holder that started this may have been recycled onto another
                    // message, so redraw the list rather than this one view: whichever row
                    // now shows this picture picks it up from the cache on its next bind.
                    if (bmp == null) {
                        if (b.image.tag == id) {
                            b.attachment.text = getString(R.string.signal_attachment_unavailable)
                        }
                        return@runOnUiThread
                    }
                    if (b.image.tag == id) {
                        b.image.setImageBitmap(bmp)
                        b.image.setVisible(true)
                        b.attachment.setVisible(false)
                    } else {
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        }

        /**
         * Whether this attachment is something somebody said.
         *
         * `voice` is the flag its sender set. The content type is the fallback, for anything
         * filed before that flag was read and for a client whose voice notes are not AAC --
         * see [com.wanderwildwood.kotozute.signal.VoiceNotes.isPlayableAudio] for why that
         * net is deliberately wide.
         *
         * One function because two places ask, and they must not disagree: the branch for our
         * own sent copies and the branch for everything with an id to fetch.
         */
        private fun isVoiceNote(entry: org.json.JSONObject, type: String): Boolean =
            entry.optBoolean("voice") ||
                com.wanderwildwood.kotozute.signal.VoiceNotes.isPlayableAudio(type)

        /**
         * A voice note, as a row that plays when tapped.
         *
         * Deliberately plain: a label and a leading glyph, no waveform and no moving progress
         * bar. A waveform is Signal's answer on a screen that redraws sixty times a second;
         * on this panel it is an expensive drawing of nothing anybody needs, and an animated
         * progress bar would be a full-panel refresh several times a second while it played.
         *
         * ⚠ The play state is read from the activity rather than kept in the holder, because
         * holders are recycled: a holder that scrolled away while playing would otherwise
         * carry "playing" onto whatever message it was reused for.
         */
        private fun bindVoiceNote(id: String, pending: Boolean) {
            b.attachment.setVisible(true)
            if (pending) {
                // Nothing to play: the bytes never arrived. Said plainly rather than offering
                // a button that does nothing.
                b.attachment.setText(R.string.signal_voice_message_unavailable)
                b.attachment.setOnClickListener(null)
                return
            }

            fun draw(playing: Boolean) {
                b.attachment.setText(
                    if (playing) R.string.signal_voice_message_playing
                    else R.string.signal_voice_message_play
                )
            }
            draw(playingAttachmentId == id)

            b.attachment.setOnClickListener {
                togglePlayback(id) { playing ->
                    // Only if this holder still shows the same message.
                    if (b.attachment.isAttachedToWindow) draw(playing)
                }
            }
        }
    }

    /**
     * Bounded by memory, not by how many pictures are in it.
     *
     * ⚠ It used to hold eight entries, whatever they weighed. Eight thumbnails is eight
     * megabytes or four hundred depending entirely on what somebody sent, so the bound did
     * not bound anything. An eighth of the heap is the ceiling Android's own guidance gives
     * for a bitmap cache, and it is what Glide's `MemorySizeCalculator` -- which is what
     * upstream's thumbnails are sized by -- works out from.
     */
    private val imageCache =
        object : LruCache<String, android.graphics.Bitmap>(
            (Runtime.getRuntime().maxMemory() / 8).coerceIn(2L * 1024 * 1024, 32L * 1024 * 1024).toInt()
        ) {
            override fun sizeOf(key: String, value: android.graphics.Bitmap): Int =
                SignalAttachment.bitmapBytes(value)
        }

    /** Attachment ids currently being fetched, so a rebind does not fetch them again. */
    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())
}
