package com.wanderwildwood.kotozute.feature.signal

import android.content.Context
import android.content.Intent
import android.view.View
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.base.QkThemedActivity
import com.wanderwildwood.kotozute.common.util.DateFormatter
import com.wanderwildwood.kotozute.common.util.einkDialog
import com.wanderwildwood.kotozute.common.util.extensions.setVisible
import com.wanderwildwood.kotozute.databinding.SignalMediaGridItemBinding
import com.wanderwildwood.kotozute.databinding.SignalThreadInfoActivityBinding
import com.wanderwildwood.kotozute.model.SignalMessage
import com.wanderwildwood.kotozute.repository.SignalRepository
import dagger.android.AndroidInjection
import org.json.JSONArray
import javax.inject.Inject
import kotlin.concurrent.thread
import com.wanderwildwood.kotozute.common.util.extensions.turnsAPageOnSwipe
import com.wanderwildwood.kotozute.common.util.extensions.stopAnimatingItems

/**
 * What the SMS side calls Details, for a Signal thread: who this is, the pictures the
 * conversation has carried, and the one setting that applies to it.
 *
 * Not the SMS screen with a different source behind it. That one is built from Recipients
 * and MmsParts, and offers notification channels and deleting, neither of which a Signal
 * thread has an equivalent for here.
 *
 * ⚠ It also said blocking could not work, on the reasoning that it was a Signal-side action
 * the bridge would not carry. This screen has blocked and unblocked since the account's
 * blocked list became readable -- see the row itself, which is offered only once this device
 * has that list.
 */
class SignalThreadInfoActivity : QkThemedActivity() {

    @Inject lateinit var signalRepo: SignalRepository
    @Inject lateinit var dateFormatter: DateFormatter

    /** The text conversation this one is joined to, when there is one. */
    private var linkedConversation: Long? = null

    private lateinit var binding: SignalThreadInfoActivityBinding
    private lateinit var threadKey: String
    private var isArchived = false
    private var blockArmed = false
    private var deleteArmed = false
    private val disarmDelete = Runnable {
        deleteArmed = false
        binding.deleteThread.title = getString(R.string.info_delete)
        binding.deleteThread.summary = deleteSummary()
    }
    /** What the row does: block, or take a block back. Read from the list this device holds. */
    private var blocked = false
    private var safetyArmed = false

    private val disarmSafety = Runnable {
        safetyArmed = false
        binding.safetyAccept.title = getString(R.string.signal_safety_accept_title)
    }

    private val disarmBlock = Runnable {
        blockArmed = false
        binding.block.title = getString(blockRowTitle())
    }

    private fun blockRowTitle() = if (blocked) R.string.info_unblock else R.string.info_block

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        binding = SignalThreadInfoActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        threadKey = intent.getStringExtra(EXTRA_KEY).orEmpty()
        if (threadKey.isBlank()) { finish(); return }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        // setTitle, not the view: QkActivity overrides setTitle to write into R.id.toolbarTitle,
        // so anything set in the layout is replaced by the activity label the moment the base
        // class runs. Setting the title is the supported way to fill that view.
        title = getString(R.string.signal_info_title)

        bindLink()
        bindDelete()
        refreshLinkRow()

        binding.timer.setOnClickListener { pickTimer() }
        binding.nickname.setOnClickListener { editNickname() }

        binding.archive.setOnClickListener {
            isArchived = !isArchived
            signalRepo.setArchived(threadKey, isArchived)
            renderArchive()
            Toast.makeText(
                this,
                if (isArchived) R.string.signal_archived_toast else R.string.signal_unarchived_toast,
                Toast.LENGTH_SHORT
            ).show()
        }

        // Offered only once this device has the account's blocked list. Signal syncs that
        // list whole, so a device without one cannot add to it -- and a row that can only
        // answer "not yet" is worse than a row that is not there. Read off the Looper: it
        // opens the protocol store.
        thread(isDaemon = true) {
            val canBlock = runCatching { signalRepo.canBlock() }.getOrDefault(false)
            val alreadyBlocked = runCatching { signalRepo.isBlocked(threadKey) }.getOrDefault(false)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                blocked = alreadyBlocked
                binding.block.title = getString(blockRowTitle())
                binding.block.setVisible(canBlock && threadKey.startsWith("direct:"))
            }
        }

        // Blocking reaches the Signal account, so it arms and confirms rather than acting
        // on one tap: the row says what a second tap will do, and disarms itself after a
        // few seconds so a stray tap does not leave a live trigger sitting there.
        binding.block.setOnClickListener {
            if (!blockArmed) {
                blockArmed = true
                binding.block.title = getString(
                    if (blocked) R.string.signal_unblock_armed else R.string.signal_block_armed
                )
                binding.block.postDelayed(disarmBlock, ARM_TIMEOUT_MS)
                return@setOnClickListener
            }
            binding.block.removeCallbacks(disarmBlock)
            blockArmed = false
            binding.block.title = getString(blockRowTitle())
            val wanted = !blocked
            thread(isDaemon = true) {
                // Through the one rule, so this screen, the list's menu and the browser
                // cannot disagree about what blocking a row means.
                val result = runCatching {
                    val ok = signalRepo.actOnPerson(
                        threadKey,
                        if (wanted) com.wanderwildwood.kotozute.repository.SignalRepository.PersonAction.BLOCK
                        else com.wanderwildwood.kotozute.repository.SignalRepository.PersonAction.UNBLOCK
                    )
                    if (!ok) throw IllegalStateException("blocking did not work")
                }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    // Said either way. A block that failed silently would leave someone
                    // believing they had stopped hearing from a person they had not.
                    Toast.makeText(
                        this,
                        when {
                            result.isFailure && wanted -> R.string.signal_block_failed
                            result.isFailure -> R.string.signal_unblock_failed
                            wanted -> R.string.signal_blocked_toast
                            else -> R.string.signal_unblocked_toast
                        },
                        Toast.LENGTH_LONG
                    ).show()
                    if (result.isSuccess) {
                        blocked = wanted
                        binding.block.title = getString(blockRowTitle())
                        // Leaving is right for a block -- the conversation is one you have
                        // just stopped -- and wrong for taking one back.
                        if (wanted) finish()
                    }
                }
            }
        }

        // Realm and the network both off the main thread; this screen opens over a
        // conversation and a stutter there is the one place it would be noticed.
        thread(isDaemon = true) { load() }
        if (threadKey.startsWith("group:")) thread(isDaemon = true) { loadGroup() }
        thread(isDaemon = true) { loadIdentity() }
        thread(isDaemon = true) { loadNickname() }
    }

    /** The nickname as held, or null where this conversation has nobody to give one to. */
    private var nickname: SignalRepository.Nickname? = null

    private fun loadNickname() {
        val held = runCatching { signalRepo.nickname(threadKey) }.getOrNull()
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            nickname = held
            binding.nickname.setVisible(held != null)
            if (held == null) return@runOnUiThread
            val name = listOf(held.given, held.family).filter { it.isNotBlank() }.joinToString(" ")
            binding.nickname.summary = listOf(name, held.note).filter { it.isNotBlank() }
                .joinToString("\n")
                .ifBlank { getString(R.string.signal_nickname_none) }
        }
    }

    /**
     * Upstream's `NicknameActivity`, as a dialog: first name, last name and a note, at its
     * limits (`NicknameViewModel`: 26 and 240). Saving with every part blank takes them away,
     * and Delete does that in one go -- asking in its own face, not with a second dialog.
     */
    private fun editNickname() {
        val held = nickname ?: return
        val pad = (20 * resources.displayMetrics.density).toInt()
        fun field(hint: Int, text: String, max: Int, type: Int) = android.widget.EditText(this).apply {
            setHint(hint)
            setText(text)
            inputType = type
            filters = arrayOf(android.text.InputFilter.LengthFilter(max))
        }
        val nameType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        val given = field(R.string.signal_nickname_first, held.given, NICKNAME_NAME_MAX, nameType)
        val family = field(R.string.signal_nickname_last, held.family, NICKNAME_NAME_MAX, nameType)
        val note = field(
            R.string.signal_nickname_note, held.note, NICKNAME_NOTE_MAX,
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        )
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(com.wanderwildwood.kotozute.common.widget.QkTextView(this@SignalThreadInfoActivity).apply {
                setText(R.string.signal_nickname_private)
            })
            addView(given)
            addView(family)
            addView(note)
        }
        val hasOne = listOf(held.given, held.family, held.note).any { it.isNotBlank() }
        val dialog = einkDialog()
            .setTitle(R.string.signal_nickname)
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.signal_nickname_save) { _, _ ->
                saveNickname(
                    SignalRepository.Nickname(
                        given.text.toString(), family.text.toString(), note.text.toString()
                    )
                )
            }
            .apply { if (hasOne) setNeutralButton(R.string.signal_nickname_delete, null) }
            .show()
        if (hasOne) {
            val delete = dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)
            var armed = false
            val disarm = Runnable { armed = false; delete.setText(R.string.signal_nickname_delete) }
            delete.setOnClickListener {
                if (!armed) {
                    armed = true
                    delete.setText(R.string.signal_nickname_delete_armed)
                    delete.postDelayed(disarm, ARM_TIMEOUT_MS)
                    return@setOnClickListener
                }
                delete.removeCallbacks(disarm)
                dialog.dismiss()
                saveNickname(SignalRepository.Nickname("", "", ""))
            }
        }
    }

    private fun saveNickname(wanted: SignalRepository.Nickname) {
        thread(isDaemon = true) {
            val ok = runCatching { signalRepo.setNickname(threadKey, wanted) }.getOrDefault(false)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (!ok) Toast.makeText(this, R.string.signal_nickname_failed, Toast.LENGTH_LONG).show()
                thread(isDaemon = true) { loadNickname() }
                // The name at the top is the conversation's title, which the nickname now sets.
                thread(isDaemon = true) { load() }
            }
        }
    }

    /**
     * Joins this conversation to the text conversation with the same person.
     *
     * By hand, because for most people there is nothing to join on: Signal stopped handing
     * out phone numbers, so a Signal thread and a text thread with one person share no
     * identifier at all. Where they do share a number the crossing already happens on its
     * own and this row only offers to override it.
     *
     * Kept on this phone and nowhere else. It is one person's view of who two conversations
     * belong to, it tells Signal nothing, and it is undone by the same row.
     */
    private fun bindLink() {
        binding.link.setOnClickListener {
            thread {
                val linked = signalRepo.linkedConversationId(threadKey)
                val conversations = runCatching {
                    conversationRepo.getConversationsSnapshot(unreadAtTop = false)
                        .plus(conversationRepo.getConversationsSnapshot(unreadAtTop = false, archived = true))
                }.getOrDefault(emptyList())
                // Read on this thread while the objects are still live, because a Realm
                // object does not travel: the names have to be taken here, as strings.
                val choices = conversations.map { conversation ->
                    conversation.id to conversation.getTitle()
                }.filter { it.second.isNotBlank() }
                runOnUiThread { showLinkPicker(choices, linked) }
            }
        }
    }

    private fun showLinkPicker(choices: List<Pair<Long, String>>, linked: Long?) {
        if (isFinishing) return
        if (choices.isEmpty()) {
            Toast.makeText(this, R.string.info_link_none, Toast.LENGTH_SHORT).show()
            return
        }
        val builder = einkDialog()
            .setTitle(R.string.info_link_pick)
            .setItems(choices.map { it.second }.toTypedArray()) { _, which ->
                signalRepo.linkConversation(threadKey, choices[which].first)
                refreshLinkRow()
            }
        // Only where there is something to undo. An "unlink" on a conversation that is not
        // linked is a button that does nothing, which reads as one that failed.
        if (linked != null) {
            builder.setNegativeButton(R.string.info_link_unlink) { _, _ ->
                signalRepo.linkConversation(threadKey, null)
                refreshLinkRow()
            }
        }
        builder.show()
    }

    private fun refreshLinkRow() {
        thread {
            val linked = signalRepo.linkedConversationId(threadKey)
            val name = linked?.let {
                runCatching { conversationRepo.getConversation(it)?.getTitle() }.getOrNull()
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                binding.link.summary = name?.let { getString(R.string.info_link_linked, it) }
                // Said before it is pressed, not discovered afterwards: where the rails are
                // joined, blocking stops both of them.
                binding.block.summary =
                    if (name != null) getString(R.string.info_block_both) else null
                linkedConversation = linked?.takeIf { it != 0L && name != null }
                if (!deleteArmed) binding.deleteThread.summary = deleteSummary()
            }
        }
    }

    /** What the delete row says it will take, which depends on whether the rails are joined. */
    private fun deleteSummary(): String = getString(
        if (linkedConversation != null) R.string.info_delete_both else R.string.info_delete_summary
    )

    /**
     * Deletes this phone's copy of the conversation.
     *
     * Armed and confirmed, like Block, and for a harder reason: this is the only copy there
     * is. Signal hands a linked device no history, so what is here arrived while this phone
     * was linked or was imported into it, and nothing will send it again. The armed wording
     * says so, and points at the export.
     *
     * Nothing here reaches the Signal account. The other person keeps their copy and a new
     * message starts the conversation over.
     */
    private fun bindDelete() {
        binding.deleteThread.summary = deleteSummary()
        binding.deleteThread.setOnClickListener {
            if (!deleteArmed) {
                deleteArmed = true
                binding.deleteThread.title = getString(R.string.info_delete_confirm)
                binding.deleteThread.summary = getString(R.string.info_delete_armed_summary)
                binding.deleteThread.postDelayed(disarmDelete, ARM_TIMEOUT_MS)
                return@setOnClickListener
            }
            binding.deleteThread.removeCallbacks(disarmDelete)
            deleteArmed = false
            thread {
                val result = runCatching {
                    val gone = signalRepo.countMessages(threadKey)
                    // Both halves, through the one rule: deleting one would leave the other
                    // standing under the same name, which reads as the delete half failing.
                    val ok = signalRepo.actOnPerson(
                        threadKey,
                        com.wanderwildwood.kotozute.repository.SignalRepository.PersonAction.DELETE
                    )
                    if (!ok) throw IllegalStateException("nothing was deleted")
                    gone
                }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    Toast.makeText(
                        this,
                        result.getOrNull()
                            ?.let { getString(R.string.info_deleted_toast, it) }
                            ?: getString(R.string.info_delete_failed),
                        Toast.LENGTH_LONG
                    ).show()
                    if (result.isSuccess) finish()
                }
            }
        }
    }

    private fun load() {
        val thread = signalRepo.getThreadsSnapshot(archived = false)
            .plus(signalRepo.getThreadsSnapshot(archived = true))
            .firstOrNull { it.threadKey == threadKey } ?: return
        val messages = signalRepo.getMessagesSnapshot(threadKey, MAX_MESSAGES_SCANNED)
        val names = runCatching { signalRepo.senderNamesFor(threadKey) }.getOrDefault(emptyMap())
        val pictures = messages.flatMap(::imageIdsOf)
        // What they say about themselves, under their name, as Signal shows it.
        val about = runCatching { signalRepo.about(threadKey) }.getOrNull()

        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            isArchived = thread.archived
            binding.name.text = thread.title
            binding.number.text = thread.counterpartNumber
            binding.number.setVisible(thread.counterpartNumber.isNotBlank())
            binding.about.text = about.orEmpty()
            binding.about.setVisible(!about.isNullOrBlank())

            val count = resources.getQuantityString(
                R.plurals.signal_info_counts, messages.size, messages.size
            )
            val oldest = messages.minByOrNull { it.date }?.date
            binding.counts.text = if (oldest != null && oldest > 0) {
                getString(
                    R.string.signal_info_since, count, dateFormatter.getConversationTimestamp(oldest)
                )
            } else {
                count
            }

            // Only a group has members worth listing; a one-to-one thread's other party is
            // the name at the top of this screen.
            val members = names.values.filter { it.isNotBlank() }.distinct().sorted()
            val isGroup = thread.kind == "group" && members.isNotEmpty()
            binding.membersHeading.setVisible(isGroup)
            binding.members.setVisible(isGroup)
            if (isGroup) binding.members.text = members.joinToString("\n")

            binding.mediaHeading.setVisible(true)
            binding.mediaHeading.text = getString(
                if (pictures.isEmpty()) R.string.signal_info_media_none else R.string.signal_info_media
            )
            binding.media.setVisible(pictures.isNotEmpty())
            if (pictures.isNotEmpty()) {
                binding.media.layoutManager = GridLayoutManager(this, MEDIA_COLUMNS)
                binding.media.adapter = MediaAdapter(pictures)
                binding.media.turnsAPageOnSwipe()
            }
            renderArchive()
            timerSeconds = thread.expiresInSeconds.toInt()
            renderTimer()
        }
    }

    /** The conversation's disappearing-messages timer, as last read or set. */
    private var timerSeconds = 0

    private fun renderTimer() {
        val known = TIMER_CHOICES.indexOfFirst { it.first == timerSeconds }
        binding.timer.summary = if (known >= 0) getString(TIMER_CHOICES[known].second)
        else getString(R.string.info_timer_other, timerSeconds)
    }

    /** Upstream's list of timers, the one in force ticked. */
    private fun pickTimer() {
        val checked = TIMER_CHOICES.indexOfFirst { it.first == timerSeconds }
        einkDialog()
            .setTitle(R.string.info_timer)
            .setSingleChoiceItems(TIMER_CHOICES.map { getString(it.second) }.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                val seconds = TIMER_CHOICES[which].first
                if (seconds != timerSeconds) setTimer(seconds)
            }
            .show()
    }

    private fun setTimer(seconds: Int) {
        binding.timer.isEnabled = false
        binding.timer.summary = getString(R.string.info_timer_setting)
        thread(isDaemon = true) {
            val outcome = runCatching { signalRepo.setDisappearingTimer(threadKey, seconds) }
                .getOrDefault(SignalRepository.TimerSet.FAILED)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                binding.timer.isEnabled = true
                if (outcome == SignalRepository.TimerSet.SET) timerSeconds = seconds
                renderTimer()
                val why = when (outcome) {
                    SignalRepository.TimerSet.SET -> null
                    SignalRepository.TimerSet.NOT_ALLOWED -> R.string.info_timer_not_allowed
                    SignalRepository.TimerSet.NOT_A_MEMBER -> R.string.info_timer_not_member
                    SignalRepository.TimerSet.FAILED -> R.string.info_timer_failed
                }
                why?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
            }
        }
    }

    /**
     * The safety number, fetched separately from the rest: it opens the protocol store, and
     * a screen that waits for that to show a name would be the wrong trade.
     */
    private fun loadIdentity() {
        if (!threadKey.startsWith("direct:")) return // a group has one per member
        val identity = runCatching { signalRepo.identity(threadKey) }.getOrNull() ?: return
        if (identity.safetyNumber.isBlank()) return
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            binding.safetyHeading.setVisible(true)
            // Signal's own layout: sixty digits in twelve groups of five, four rows of
            // three. Six to a row is the natural half -- your thirty digits then theirs --
            // but it does not fit 480px and wrapped mid-number, which is exactly where two
            // people reading it aloud lose their place.
            binding.safetyNumber.text = identity.safetyNumber
                .filter { it.isDigit() }
                .chunked(5)
                .chunked(3)
                .joinToString("\n") { row -> row.joinToString("  ") }
            binding.safetyNumber.setVisible(true)
            binding.safetyState.setText(
                when {
                    identity.changed -> R.string.signal_safety_changed
                    identity.verified -> R.string.signal_safety_verified
                    else -> R.string.signal_safety_unverified
                }
            )
            binding.safetyState.setVisible(true)

            // Only when it has changed. Offering it the rest of the time would make accepting
            // a habit rather than a decision, and the decision is the whole mechanism.
            binding.safetyAccept.setVisible(identity.changed)
            // Marking verified is for a number that stands: a changed one is accepted first.
            binding.safetyVerify.setVisible(!identity.changed)
            binding.safetyVerify.title = getString(
                if (identity.verified) R.string.signal_safety_clear_verified else R.string.signal_safety_mark_verified
            )
            binding.safetyVerify.setOnClickListener {
                val verify = !identity.verified
                thread(isDaemon = true) {
                    val ok = runCatching { signalRepo.setVerified(threadKey, verify) }.getOrDefault(false)
                    runOnUiThread {
                        if (!ok) Toast.makeText(this, R.string.signal_safety_verify_failed, Toast.LENGTH_SHORT).show()
                        thread(isDaemon = true) { loadIdentity() }
                    }
                }
            }
            // Disarmed whenever the screen re-reads itself, so a row left armed cannot come
            // back armed after a refresh. The four-second timer would catch it anyway; this
            // makes it true rather than merely likely, which is the point of the timeout.
            binding.safetyAccept.removeCallbacks(disarmSafety)
            safetyArmed = false
            binding.safetyAccept.title = getString(R.string.signal_safety_accept_title)
            if (identity.changed) {
                binding.safetyAccept.setOnClickListener { acceptIdentityRow() }
            }
        }
    }

    /**
     * Asks before accepting, and says what accepting means.
     *
     * Deliberately not a one-tap action. A changed key is what a reinstall looks like and also
     * what interception looks like, and the app cannot tell them apart -- only the person can,
     * by checking the digits some other way. The dialog says that plainly rather than asking
     * "are you sure?", which tells nobody anything.
     */
    private fun acceptIdentityRow() {
        // ⚠ The row asks, as it does for blocking and for deleting the conversation three rows
        // away -- this was the one irreversible action on the screen still asking in a dialog.
        //
        // STYLE.md: "The row asks, not a dialog... irreversible is not only about stored
        // things", and where the action needs a warning the label cannot carry, the warning
        // takes the dash and the question follows a semicolon. The dialog it replaces was
        // titled "Accept this key?", which is the are-you-sure that section exists to stop:
        // the armed label says what a second tap means instead of asking whether you meant it.
        //
        // The friction is not lost, it moves: two taps either way, and the warning is now in
        // the row the thumb is already on rather than in a second full-panel repaint.
        if (!safetyArmed) {
            safetyArmed = true
            binding.safetyAccept.title = getString(R.string.signal_safety_accept_armed)
            binding.safetyAccept.postDelayed(disarmSafety, ARM_TIMEOUT_MS)
            return
        }
        binding.safetyAccept.removeCallbacks(disarmSafety)
        safetyArmed = false
        binding.safetyAccept.title = getString(R.string.signal_safety_accept_title)
        thread(isDaemon = true) {
            val accepted = runCatching { signalRepo.acceptIdentity(threadKey) }.getOrDefault(false)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                Toast.makeText(
                    this,
                    if (accepted) R.string.signal_safety_accepted
                    else R.string.signal_safety_accept_failed,
                    Toast.LENGTH_LONG
                ).show()
                // Re-read rather than assume: the row must disappear because the store says
                // so, not because a tap was registered.
                if (accepted) thread(isDaemon = true) { loadIdentity() }
            }
        }
    }

    private fun renderArchive() {
        binding.archive.title = getString(
            if (isArchived) R.string.signal_unarchive else R.string.signal_archive
        )
    }

    /** The attachment ids in one message that are pictures we could actually fetch. */
    private fun imageIdsOf(m: SignalMessage): List<String> {
        if (m.attachments.isBlank()) return emptyList()
        val array = runCatching { JSONArray(m.attachments) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            // Our own sent attachments carry no id: Signal assigns one on upload and never
            // reports it back, so there is nothing to fetch and nothing to show.
            // `type`; see the note in SignalThreadActivity.bindAttachment. Reading
            // `contentType` here meant this list was always empty.
            id.takeIf { it.isNotBlank() && o.optString("type").startsWith("image/") }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private inner class MediaAdapter(
        private val ids: List<String>
    ) : RecyclerView.Adapter<MediaHolder>() {
        override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
            super.onAttachedToRecyclerView(recyclerView)
            recyclerView.stopAnimatingItems()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            MediaHolder(SignalMediaGridItemBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            ))

        override fun getItemCount() = ids.size
        override fun onBindViewHolder(holder: MediaHolder, position: Int) =
            holder.bind(ids[position])
    }

    private inner class MediaHolder(
        private val b: SignalMediaGridItemBinding
    ) : RecyclerView.ViewHolder(b.root) {
        fun bind(id: String) {
            cache.get(id)?.let { b.image.setImageBitmap(it); return }
            // Tagged so a recycled holder that has scrolled on does not take someone else's
            // picture when the fetch comes back.
            b.image.setImageDrawable(null)
            b.image.tag = id
            thread(isDaemon = true) {
                val bytes = signalRepo.loadAttachment(id)
                // Bounded to the width it is drawn at; see [SignalAttachment.decodeBounded].
                val bmp = bytes?.let {
                    SignalAttachment.decodeBounded(it, b.image.width.takeIf { w -> w > 0 }
                        ?: SignalAttachment.THUMBNAIL_EDGE)
                } ?: return@thread
                cache.put(id, bmp)
                runOnUiThread { if (b.image.tag == id) b.image.setImageBitmap(bmp) }
            }
        }
    }

    /**
     * Bounded by memory, not by how many thumbnails are in it. See the note on the thread
     * screen's cache: an LRU counted in entries has no bound worth the name.
     */
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceIn(2L * 1024 * 1024, 32L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = SignalAttachment.bitmapBytes(value)
    }

    // --- a group: its name, people, permissions and link -----------------------------------

    private fun loadGroup() {
        val info = runCatching { signalRepo.groupInfo(threadKey) }.getOrNull()
        runOnUiThread { if (!isFinishing && info != null) showGroup(info) }
    }

    /** One row of the group section, as the screen's other rows are drawn. */
    private fun row(title: String, summary: String? = null, onClick: (() -> Unit)?): View =
        com.wanderwildwood.kotozute.common.widget.PreferenceView(this).apply {
            this.title = title
            this.summary = summary
            if (onClick != null) setOnClickListener { onClick() } else isEnabled = false
        }

    private fun heading(text: String): View = com.wanderwildwood.kotozute.common.widget.QkTextView(this).apply {
        this.text = text
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
    }

    /**
     * The group section: upstream's `ConversationSettingsFragment` for a group, as rows. Who
     * may change what follows the group's own permissions -- a row somebody cannot use is shown
     * without a way in rather than offered and refused.
     */
    private fun showGroup(g: SignalRepository.GroupInfo) {
        binding.membersHeading.setVisible(false)
        binding.members.setVisible(false)
        val box = binding.groupSection
        box.removeAllViews()
        box.setVisible(true)
        val canEditInfo = !g.editInfoAdminsOnly || g.selfAdmin
        val canAdd = !g.addMembersAdminsOnly || g.selfAdmin

        if (g.selfInvited) {
            box.addView(heading(getString(R.string.group_invited)))
            box.addView(row(getString(R.string.group_accept_invite), null) { groupEdit { signalRepo.answerGroupInvite(threadKey, true) } })
            box.addView(row(getString(R.string.group_decline_invite), null) { groupEdit(close = true) { signalRepo.answerGroupInvite(threadKey, false) } })
            return
        }

        box.addView(row(getString(R.string.group_name), g.title, if (canEditInfo) ({ askText(R.string.group_name, g.title) { t -> groupEdit { signalRepo.renameGroup(threadKey, t) } } }) else null))
        box.addView(row(getString(R.string.group_description), g.description.ifBlank { getString(R.string.group_description_none) },
            if (canEditInfo) ({ askText(R.string.group_description, g.description) { t -> groupEdit { signalRepo.describeGroup(threadKey, t) } } }) else null))

        box.addView(heading(resources.getQuantityString(R.plurals.group_members, g.members.size, g.members.size)))
        if (canAdd) box.addView(row(getString(R.string.group_add_members), null) { pickPeopleToAdd(g) })
        g.members.forEach { m ->
            val summary = listOfNotNull(
                getString(R.string.group_admin).takeIf { m.admin }
            ).joinToString().ifBlank { null }
            box.addView(row(m.name, summary, if (g.selfAdmin && !m.self) ({ memberActions(m) }) else null))
        }
        if (g.requesting.isNotEmpty() && g.selfAdmin) {
            box.addView(heading(getString(R.string.group_requests)))
            g.requesting.forEach { m -> box.addView(row(m.name, getString(R.string.group_request_summary)) { answerRequest(m) }) }
        }
        if (g.pending.isNotEmpty()) {
            box.addView(heading(resources.getQuantityString(R.plurals.group_invited_count, g.pending.size, g.pending.size)))
            g.pending.forEach { m -> box.addView(row(m.name, null, null)) }
        }

        if (g.selfAdmin) {
            box.addView(heading(getString(R.string.group_permissions)))
            box.addView(row(getString(R.string.group_who_edits_info), whoWords(g.editInfoAdminsOnly)) {
                groupEdit { signalRepo.setEditInfoAdminsOnly(threadKey, !g.editInfoAdminsOnly) }
            })
            box.addView(row(getString(R.string.group_who_adds_members), whoWords(g.addMembersAdminsOnly)) {
                groupEdit { signalRepo.setAddMembersAdminsOnly(threadKey, !g.addMembersAdminsOnly) }
            })
            box.addView(row(getString(R.string.group_who_sends), whoWords(g.sendAdminsOnly)) {
                groupEdit { signalRepo.setSendAdminsOnly(threadKey, !g.sendAdminsOnly) }
            })
            box.addView(row(getString(R.string.group_link), linkWords(g.link)) { linkActions(g) })
        }

        // Down with Block and Delete: the rows that end something go last.
        binding.groupLeave.setVisible(true)
        binding.groupLeave.setOnClickListener { confirmLeave(g) }
    }

    private fun whoWords(adminsOnly: Boolean) = getString(if (adminsOnly) R.string.group_only_admins else R.string.group_all_members)

    private fun linkWords(state: String) = getString(
        when (state) {
            "ON" -> R.string.group_link_on
            "APPROVAL" -> R.string.group_link_approval
            else -> R.string.group_link_off
        }
    )

    /** A group change, off the main thread, then the section read again or the screen left. */
    private fun groupEdit(close: Boolean = false, change: () -> SignalRepository.GroupEditResult) {
        thread(isDaemon = true) {
            val result = runCatching(change).getOrDefault(SignalRepository.GroupEditResult.FAILED)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val words = when (result) {
                    SignalRepository.GroupEditResult.DONE, SignalRepository.GroupEditResult.UNNEEDED -> null
                    SignalRepository.GroupEditResult.NOT_ALLOWED -> R.string.group_not_allowed
                    SignalRepository.GroupEditResult.NOT_A_MEMBER -> R.string.info_timer_not_member
                    SignalRepository.GroupEditResult.FAILED -> R.string.group_failed
                }
                words?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                if (close && words == null) finish() else thread(isDaemon = true) { loadGroup() }
            }
        }
    }

    private fun askText(@androidx.annotation.StringRes title: Int, current: String, onDone: (String) -> Unit) {
        val field = android.widget.EditText(this).apply { setText(current); setSelection(current.length) }
        einkDialog()
            .setTitle(title)
            .setView(field)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> onDone(field.text.toString()) }
            .show()
    }

    /** Signal people this phone knows, not already in the group, to add. */
    private fun pickPeopleToAdd(g: SignalRepository.GroupInfo) {
        thread(isDaemon = true) {
            val inGroup = (g.members + g.pending).map { it.aci }.toSet()
            val people = runCatching { signalRepo.getThreadsSnapshot(archived = false) }.getOrDefault(emptyList())
                .filter { it.kind == "direct" && it.counterpartUuid.isNotBlank() && it.counterpartUuid !in inGroup && !it.counterpartUuid.startsWith("+") }
                .map { it.counterpartUuid to it.title.ifBlank { it.counterpartUuid.take(8) } }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (people.isEmpty()) {
                    Toast.makeText(this, R.string.group_nobody_to_add, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val chosen = BooleanArray(people.size)
                einkDialog()
                    .setTitle(R.string.group_add_members)
                    .setMultiChoiceItems(people.map { it.second }.toTypedArray(), chosen) { _, i, on -> chosen[i] = on }
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.group_add) { _, _ ->
                        val acis = people.filterIndexed { i, _ -> chosen[i] }.map { it.first }
                        if (acis.isNotEmpty()) groupEdit { signalRepo.addToGroup(threadKey, acis) }
                    }
                    .show()
            }
        }
    }

    private fun memberActions(m: SignalRepository.GroupMember) {
        val actions = listOf(
            getString(if (m.admin) R.string.group_remove_admin else R.string.group_make_admin) to {
                groupEdit { signalRepo.setGroupAdmin(threadKey, m.aci, !m.admin) }
            },
            getString(R.string.group_remove_member) to {
                einkDialog()
                    .setMessage(getString(R.string.group_remove_confirm, m.name))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.group_remove_member) { _, _ -> groupEdit { signalRepo.removeFromGroup(threadKey, m.aci) } }
                    .show()
            }
        )
        einkDialog()
            .setTitle(m.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .show()
    }

    private fun answerRequest(m: SignalRepository.GroupMember) {
        einkDialog()
            .setTitle(m.name)
            .setMessage(R.string.group_request_question)
            .setNegativeButton(R.string.group_deny) { _, _ -> groupEdit { signalRepo.answerJoinRequest(threadKey, m.aci, false) } }
            .setPositiveButton(R.string.group_approve) { _, _ -> groupEdit { signalRepo.answerJoinRequest(threadKey, m.aci, true) } }
            .show()
    }

    /** The group link: off, on, on with approval; share it; make a new one. */
    private fun linkActions(g: SignalRepository.GroupInfo) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        listOf("OFF" to R.string.group_link_off, "ON" to R.string.group_link_on, "APPROVAL" to R.string.group_link_approval)
            .filter { it.first != g.link }
            .forEach { (state, words) -> actions += getString(words) to { groupEdit { signalRepo.setGroupLink(threadKey, state) } } }
        g.linkUrl?.let { url ->
            actions += getString(R.string.group_link_share) to {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null))
            }
            actions += getString(R.string.group_link_reset) to { groupEdit { signalRepo.resetGroupLink(threadKey) } }
        }
        einkDialog()
            .setTitle(R.string.group_link)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .show()
    }

    /**
     * Leaving, as upstream asks it: the last admin chooses who takes over first, since a group
     * with members and no admin can never change again.
     */
    private fun confirmLeave(g: SignalRepository.GroupInfo) {
        val others = g.members.filter { !it.self }
        val lastAdmin = g.selfAdmin && g.members.none { it.admin && !it.self } && others.isNotEmpty()
        if (lastAdmin) {
            einkDialog()
                .setTitle(R.string.group_choose_admin)
                .setItems(others.map { it.name }.toTypedArray()) { _, i ->
                    groupEdit(close = true) { signalRepo.leaveGroup(threadKey, others[i].aci) }
                }
                .show()
            return
        }
        einkDialog()
            .setMessage(R.string.group_leave_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.group_leave) { _, _ -> groupEdit(close = true) { signalRepo.leaveGroup(threadKey, null) } }
            .show()
    }

    companion object {
        /** Long enough to read the armed label, short enough not to stay live. */
        private const val ARM_TIMEOUT_MS = 4000L
        private const val EXTRA_KEY = "threadKey"
        private const val MEDIA_COLUMNS = 3

        /** Upstream's `NicknameViewModel.NAME_MAX_LENGTH` and `NOTE_MAX_LENGTH`. */
        private const val NICKNAME_NAME_MAX = 26
        private const val NICKNAME_NOTE_MAX = 240

        /** Upstream's `ExpireTimerSettingsFragment__values`, in its order. */
        private val TIMER_CHOICES = listOf(
            0 to R.string.info_timer_off,
            2_419_200 to R.string.info_timer_4_weeks,
            604_800 to R.string.info_timer_1_week,
            86_400 to R.string.info_timer_1_day,
            28_800 to R.string.info_timer_8_hours,
            3_600 to R.string.info_timer_1_hour,
            300 to R.string.info_timer_5_minutes,
            30 to R.string.info_timer_30_seconds
        )

        /**
         * A conversation's whole history is not needed to describe it, and reading every
         * message of a long one off the main thread still costs memory for nothing.
         */
        private const val MAX_MESSAGES_SCANNED = 2000

        fun intentFor(context: Context, threadKey: String): Intent =
            Intent(context, SignalThreadInfoActivity::class.java).putExtra(EXTRA_KEY, threadKey)
    }
}
