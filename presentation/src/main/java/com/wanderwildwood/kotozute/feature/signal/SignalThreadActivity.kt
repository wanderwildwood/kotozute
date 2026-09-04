package com.wanderwildwood.kotozute.feature.signal

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
import com.wanderwildwood.kotozute.common.base.QkThemedActivity
import com.wanderwildwood.kotozute.common.util.extensions.setVisible
import com.wanderwildwood.kotozute.databinding.SignalMessageListItemBinding
import com.wanderwildwood.kotozute.databinding.SignalThreadActivityBinding
import com.wanderwildwood.kotozute.model.SignalMessage
import com.wanderwildwood.kotozute.repository.SignalRepository
import com.wanderwildwood.kotozute.common.util.DateFormatter
import dagger.android.AndroidInjection
import io.reactivex.disposables.CompositeDisposable
import io.realm.RealmResults
import org.json.JSONArray
import android.util.LruCache
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

class SignalThreadActivity : QkThemedActivity() {

    @Inject lateinit var signalRepo: SignalRepository
    @Inject lateinit var dateFormatter: DateFormatter
    @Inject lateinit var notifications: SignalNotifications
    @Inject lateinit var navigator: com.wanderwildwood.kotozute.common.Navigator

    private lateinit var binding: SignalThreadActivityBinding
    private val disposables = CompositeDisposable()
    private var messages: RealmResults<SignalMessage>? = null
    /** The SMS thread for the same person, when there is one. */
    private var smsThreadId: Long = 0L
    private lateinit var adapter: MessageAdapter
    private var isArchived: Boolean = false
    private var isPinned: Boolean = false
    private var isMuted: Boolean = false

    /** Only groups need these; resolved once per load rather than per drawn row. */
    private var senderNames: Map<String, String> = emptyMap()
    private val isGroup: Boolean get() = threadKey.startsWith("group:")
    private lateinit var threadKey: String

    /** The picked file, already a data URI. Held until the message is actually sent. */
    private var pendingAttachment: String? = null
    private var pendingName: String? = null

    private val picker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> if (uri != null) attach(uri) }

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
            val blocked = when {
                !conn.bridgeReachable -> getString(R.string.signal_cannot_send_bridge)
                !conn.signalConnected -> getString(R.string.signal_cannot_send_signal)
                else -> null
            }
            runOnUiThread {
                binding.cannotSend.text = blocked.orEmpty()
                binding.cannotSend.setVisible(blocked != null)
                binding.send.isEnabled = blocked == null
                binding.message.isEnabled = blocked == null
            }
        })

        binding.send.setOnClickListener { send() }
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
        // The rail badge doubles as the way across, and is the only way: it says which rail
        // you are on either way, and when this person also has an SMS thread it gains an
        // arrow and a tap takes you there.
        binding.railBadge.setOnClickListener {
            if (smsThreadId != 0L) navigator.showConversation(smsThreadId)
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

    private fun clearAttachment() {
        pendingAttachment = null
        pendingName = null
        binding.pending.setVisible(false)
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
                    .onFailure {
                        // The message stays in the box, so nothing the user typed is lost.
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
     * The same person can be on both rails. Rather than merge the two conversations --
     * which would mean one composer having to decide silently which way a reply goes --
     * each thread stays itself and offers a way across.
     */
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
                                name = it.title.ifBlank { it.counterpartNumber }
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
     * Copy or share one message. A dialog rather than a selection mode: selection earns its
     * complexity when you act on many messages at once, and here there is nothing yet that
     * takes more than one.
     */
    private fun showMessageActions(body: String) {
        val actions = listOf(
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
        AlertDialog.Builder(this)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    /**
     * The short row of emoji Signal itself offers, and a way to take one back.
     *
     * Six, not a picker. A reaction is a quick thing and a grid of a thousand glyphs on a
     * 480px e-ink screen is not quick -- the phone's own emoji panel exists for the
     * composer, where somebody is actually writing.
     */
    private fun askForReaction(messageId: String) {
        val choices = listOf("\u2764\ufe0f", "\uD83D\uDC4D", "\uD83D\uDC4E", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDE22")
        val labels = (choices + getString(R.string.signal_reaction_remove)).toTypedArray()

        AlertDialog.Builder(this)
            .setItems(labels) { _, which ->
                val remove = which == choices.size
                val emoji = if (remove) mineOn(messageId) else choices[which]
                if (emoji.isBlank()) return@setItems
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
            .show()
    }

    /**
     * Which emoji this account already put on a message, so "remove" knows what to take
     * off -- Signal removes a specific reaction, not whatever happens to be there.
     */
    private fun mineOn(messageId: String): String = runCatching {
        io.realm.Realm.getDefaultInstance().use { realm ->
            val row = realm.where(SignalMessage::class.java).equalTo("id", messageId).findFirst()
                ?: return@use ""
            val arr = JSONArray(row.reactions.ifBlank { "[]" })
            // Ours is recorded as "me" when the echo of it comes back, so this needs no
            // network call and no copy of the account's uuid.
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                if (e.optString("who") == "me") return@use e.optString("emoji")
            }
            ""
        }
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
        super.onDestroy()
    }

    companion object {
        /** Plain ASCII on purpose: the Kompakt's font has no glyph for the nicer arrows. */
        private const val RAIL_SWITCH_ARROW = ">"

        /**
         * Which conversation is on screen, so a notification is not raised about a message
         * the user is watching arrive.
         */
        @Volatile private var visibleThreadKey: String? = null

        fun isVisible(threadKey: String): Boolean = visibleThreadKey == threadKey
    }

    private inner class MessageAdapter : RecyclerView.Adapter<MessageHolder>() {
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
            // is gone, which is the whole promise. The bridge keeps the row so the thread
            // does not have a silent hole in it; drawn as an empty bubble it was the hole
            // anyway, and indistinguishable from a rendering fault.
            val text = if (m.body.isEmpty() && m.viewOnce) {
                getString(R.string.signal_view_once_received)
            } else {
                m.body
            }
            b.body.text = text
            b.body.setVisible(text.isNotEmpty())
            bindReactions(m)

            // Hold a message to react to it, which is the gesture every other messenger
            // uses for this. The id is captured now rather than read from the holder later:
            // a holder is recycled onto another message and the menu would then act on
            // whichever one had scrolled into its place.
            val messageId = m.id
            b.root.setOnLongClickListener {
                askForReaction(messageId)
                true
            }
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
            val side = if (m.outgoing) Gravity.END else Gravity.START
            (b.root as? android.widget.LinearLayout)?.let { root ->
                // The timestamp stays centred whichever side the message is on, so only the
                // children below it follow the sender.
                listOf(b.sender, b.image, b.attachment, b.body).forEach { child ->
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
                    emojiOnly = false,
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
            // since it was QKSMS; this is the small version of it -- the two things anyone
            // actually reaches for, on the gesture they will already try.
            b.body.setOnLongClickListener {
                if (m.body.isNotBlank()) showMessageActions(m.body)
                m.body.isNotBlank()
            }

            bindAttachment(m)
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
            val type = first.optString("contentType")

            // Our own sent attachments carry no id: Signal assigns one on upload and does
            // not report it back. There is nothing to fetch, but the sender should still
            // see that the message carried something.
            if (id.isBlank()) {
                b.attachment.text = getString(
                    R.string.signal_attachment_other,
                    type.ifBlank { getString(R.string.signal_attachment_image) }
                )
                b.attachment.setVisible(true)
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
                val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
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
    }

    /** Small: a handful of pictures in view at once, and e-ink shows few at a time. */
    private val imageCache = LruCache<String, android.graphics.Bitmap>(8)

    /** Attachment ids currently being fetched, so a rebind does not fetch them again. */
    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())
}
