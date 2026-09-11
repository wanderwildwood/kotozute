package com.wanderwildwood.kotozute.feature.signal

import android.content.Context
import android.content.Intent
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

/**
 * What the SMS side calls Details, for a Signal thread: who this is, the pictures the
 * conversation has carried, and the one setting that applies to it.
 *
 * Not the SMS screen with a different source behind it. That one is built from Recipients
 * and MmsParts, and offers notification channels, blocking and deleting -- none of which a
 * Signal thread has an equivalent for here. Blocking in particular is a Signal-side action
 * and the bridge's method allowlist does not carry it; offering a button that cannot work
 * would be worse than not offering one.
 */
class SignalThreadInfoActivity : QkThemedActivity() {

    @Inject lateinit var signalRepo: SignalRepository
    @Inject lateinit var dateFormatter: DateFormatter
    @Inject lateinit var markBlocked: com.wanderwildwood.kotozute.interactor.MarkBlocked
    @Inject lateinit var markUnblocked: com.wanderwildwood.kotozute.interactor.MarkUnblocked
    @Inject lateinit var deleteConversations:
        com.wanderwildwood.kotozute.interactor.DeleteConversations

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
                binding.block.setVisible(canBlock)
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
                val result = runCatching {
                    signalRepo.setBlocked(threadKey, wanted)
                    // And their texts, where the two are one conversation. A row that stands
                    // for both rails and blocks only one of them stops half of what the
                    // person can send -- which is not what anybody pressing this meant.
                    signalRepo.linkedConversationId(threadKey)
                        ?.takeIf { it != 0L }
                        ?.let { id ->
                            if (wanted) {
                                markBlocked.execute(
                                    com.wanderwildwood.kotozute.interactor.MarkBlocked.Params(
                                        listOf(id), prefs.blockingManager.get(), null
                                    )
                                )
                            } else {
                                markUnblocked.execute(listOf(id))
                            }
                        }
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

        // Realm and the bridge both off the main thread; this screen opens over a
        // conversation and a stutter there is the one place it would be noticed.
        thread(isDaemon = true) { load() }
        thread(isDaemon = true) { loadIdentity() }
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
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
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
            val alsoText = linkedConversation
            thread {
                val result = runCatching {
                    val gone = signalRepo.deleteThread(threadKey)
                    // Both halves, because the inbox shows one row for both: deleting one
                    // would leave the other standing under the same name, which reads as
                    // the delete having half failed.
                    alsoText?.let { deleteConversations.execute(listOf(it)) }
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

        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            isArchived = thread.archived
            binding.name.text = thread.title
            binding.number.text = thread.counterpartNumber
            binding.number.setVisible(thread.counterpartNumber.isNotBlank())

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
        }
    }

    /**
     * The safety number, fetched separately from the rest: it needs the bridge, and a
     * screen that waits for the network to show a name would be the wrong trade.
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
            if (identity.changed) {
                binding.safetyAccept.setOnClickListener { confirmAcceptIdentity() }
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
    private fun confirmAcceptIdentity() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.signal_safety_accept_confirm)
            .setMessage(R.string.signal_safety_accept_confirm_body)
            .setNegativeButton(R.string.button_cancel, null)
            .setPositiveButton(R.string.button_continue) { _, _ ->
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
                        // Re-read rather than assume: the row must disappear because the
                        // store says so, not because a tap was registered.
                        if (accepted) thread(isDaemon = true) { loadIdentity() }
                    }
                }
            }
            .show()
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
            id.takeIf { it.isNotBlank() && o.optString("contentType").startsWith("image/") }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private inner class MediaAdapter(
        private val ids: List<String>
    ) : RecyclerView.Adapter<MediaHolder>() {
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
                val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: return@thread
                cache.put(id, bmp)
                runOnUiThread { if (b.image.tag == id) b.image.setImageBitmap(bmp) }
            }
        }
    }

    /** Small: an e-ink screen shows a handful of thumbnails at a time. */
    private val cache = LruCache<String, Bitmap>(12)

    companion object {
        /** Long enough to read the armed label, short enough not to stay live. */
        private const val ARM_TIMEOUT_MS = 4000L
        private const val EXTRA_KEY = "threadKey"
        private const val MEDIA_COLUMNS = 3

        /**
         * A conversation's whole history is not needed to describe it, and reading every
         * message of a long one off the main thread still costs memory for nothing.
         */
        private const val MAX_MESSAGES_SCANNED = 2000

        fun intentFor(context: Context, threadKey: String): Intent =
            Intent(context, SignalThreadInfoActivity::class.java).putExtra(EXTRA_KEY, threadKey)
    }
}
