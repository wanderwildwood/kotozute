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

    private lateinit var binding: SignalThreadInfoActivityBinding
    private lateinit var threadKey: String
    private var isArchived = false
    private var blockArmed = false
    private val disarmBlock = Runnable {
        blockArmed = false
        binding.block.title = getString(R.string.info_block)
    }

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

        // Blocking reaches the Signal account, so it arms and confirms rather than acting
        // on one tap: the row says what a second tap will do, and disarms itself after a
        // few seconds so a stray tap does not leave a live trigger sitting there.
        binding.block.setOnClickListener {
            if (!blockArmed) {
                blockArmed = true
                binding.block.title = getString(R.string.signal_block_armed)
                binding.block.postDelayed(disarmBlock, ARM_TIMEOUT_MS)
                return@setOnClickListener
            }
            binding.block.removeCallbacks(disarmBlock)
            blockArmed = false
            binding.block.title = getString(R.string.info_block)
            thread(isDaemon = true) {
                val result = runCatching { signalRepo.setBlocked(threadKey, true) }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    // Said either way. A block that failed silently would leave someone
                    // believing they had stopped hearing from a person they had not.
                    Toast.makeText(
                        this,
                        if (result.isSuccess) R.string.signal_blocked_toast
                        else R.string.signal_block_failed,
                        Toast.LENGTH_LONG
                    ).show()
                    if (result.isSuccess) finish()
                }
            }
        }

        // Realm and the bridge both off the main thread; this screen opens over a
        // conversation and a stutter there is the one place it would be noticed.
        thread(isDaemon = true) { load() }
        thread(isDaemon = true) { loadIdentity() }
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
            // Grouped in fives, the way Signal prints it, so two people can read it aloud
            // to each other without losing their place.
            binding.safetyNumber.text = identity.safetyNumber
                .filter { it.isDigit() }
                .chunked(5)
                .chunked(6)
                .joinToString("\n") { row -> row.joinToString(" ") }
            binding.safetyNumber.setVisible(true)
            binding.safetyState.setText(
                when {
                    identity.changed -> R.string.signal_safety_changed
                    identity.verified -> R.string.signal_safety_verified
                    else -> R.string.signal_safety_unverified
                }
            )
            binding.safetyState.setVisible(true)
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
