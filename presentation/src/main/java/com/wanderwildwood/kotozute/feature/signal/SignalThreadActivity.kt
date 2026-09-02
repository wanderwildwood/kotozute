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
import kotlin.math.abs
import java.util.concurrent.TimeUnit
import com.wanderwildwood.kotozute.common.util.extensions.dpToPx
import com.wanderwildwood.kotozute.feature.compose.BubbleUtils

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
    private var isArchived: Boolean = false

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

        val adapter = MessageAdapter()
        val lm = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.recyclerView.layoutManager = lm
        binding.recyclerView.adapter = adapter

        val results = signalRepo.getMessages(threadKey)
        messages = results
        results.addChangeListener { data, _ ->
            adapter.submit(data)
            if (data.isNotEmpty()) binding.recyclerView.scrollToPosition(data.size - 1)
            markRead(data)
        }
        adapter.submit(results)
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
        binding.attach.setOnClickListener { picker.launch("*/*") }
        binding.pending.setOnClickListener { clearAttachment() }
    }

    private fun markRead(data: List<SignalMessage>) {
        val newest = data.maxOfOrNull { it.date } ?: return
        if (data.any { !it.outgoing && !it.read }) signalRepo.markRead(threadKey, newest)
    }

    /**
     * Reads the picked file and turns it into a data URI. Images are downscaled first:
     * a phone photo base64s to several megabytes, and holding that twice over -- bytes
     * and string -- is how a small device runs out of memory mid-send.
     */
    private fun attach(uri: Uri) {
        thread(isDaemon = true) {
            val type = contentResolver.getType(uri) ?: "application/octet-stream"
            val result = runCatching {
                val bytes = if (type.startsWith("image/")) {
                    downscaleImage(uri) ?: readBytes(uri)
                } else {
                    readBytes(uri)
                }
                if (bytes.size > MAX_ATTACHMENT_BYTES) throw IllegalStateException("too big")
                val encodedType = if (type.startsWith("image/") && type != "image/gif") {
                    "image/jpeg"
                } else {
                    type
                }
                "data:$encodedType;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
            runOnUiThread {
                result.onSuccess { dataUri ->
                    pendingAttachment = dataUri
                    pendingName = displayName(uri) ?: type
                    binding.pending.text = getString(R.string.signal_attached, pendingName)
                    binding.pending.setVisible(true)
                }.onFailure {
                    val msg = if (it is IllegalStateException) {
                        R.string.signal_attach_too_big
                    } else {
                        R.string.signal_attach_failed
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** A MediaStore uri's last path segment is a row id, so ask for the real name. */
    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull()

    private fun readBytes(uri: Uri): ByteArray =
        contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("cannot read $uri")

    /** Decodes at a reduced sample size, then recompresses. Null if it is not an image. */
    private fun downscaleImage(uri: Uri): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > MAX_IMAGE_EDGE || bounds.outHeight / sample > MAX_IMAGE_EDGE) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            bmp.recycle()
            out.toByteArray()
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
        menu?.findItem(R.id.switchToSms)?.isVisible = smsThreadId != 0L
        menu?.findItem(R.id.archiveSignal)?.setTitle(
            if (isArchived) R.string.signal_unarchive else R.string.signal_archive
        )
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.switchToSms -> {
            navigator.showConversation(smsThreadId)
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

    private fun loadThreadState(needTitle: Boolean) {
        thread(isDaemon = true) {
            val state = runCatching {
                io.realm.Realm.getDefaultInstance().use { realm ->
                    realm.where(com.wanderwildwood.kotozute.model.SignalThread::class.java)
                        .equalTo("threadKey", threadKey)
                        .findFirst()
                        ?.let { it.archived to it.title.ifBlank { it.counterpartNumber } }
                }
            }.getOrNull() ?: return@thread

            val (archived, name) = state
            runOnUiThread {
                isArchived = archived
                invalidateOptionsMenu()
                if (needTitle && name.isNotBlank()) binding.toolbarTitle.text = name
            }
        }
    }

    private fun findSmsCounterpart() {
        // The number lives on the thread row, so read it rather than guess from the key.
        thread(isDaemon = true) {
            val n = runCatching {
                io.realm.Realm.getDefaultInstance().use { realm ->
                    realm.where(com.wanderwildwood.kotozute.model.SignalThread::class.java)
                        .equalTo("threadKey", threadKey)
                        .findFirst()?.counterpartNumber.orEmpty()
                }
            }.getOrDefault("")
            if (n.isBlank()) return@thread
            // Try every number on the same address-book card, and take the conversation
            // that has been used most recently. Someone whose Signal is still on an old
            // number will have a dead SMS thread there and a live one on the new number;
            // landing on the dead one would be worse than not offering the switch at all.
            val id = runCatching {
                signalRepo.numbersForContactOf(n)
                    .mapNotNull { conversationRepo.getConversation(listOf(it)) }
                    .maxByOrNull { it.date }
                    ?.id ?: 0L
            }.getOrDefault(0L)
            if (id != 0L) runOnUiThread {
                smsThreadId = id
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

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() {
        messages?.removeAllChangeListeners()
        disposables.clear()
        super.onDestroy()
    }

    companion object {
        private const val MAX_IMAGE_EDGE = 1600
        private const val MAX_ATTACHMENT_BYTES = 24 * 1024 * 1024

        /**
         * Which conversation is on screen, so a notification is not raised about a message
         * the user is watching arrive.
         */
        @Volatile private var visibleThreadKey: String? = null

        fun isVisible(threadKey: String): Boolean = visibleThreadKey == threadKey
    }

    private inner class MessageAdapter : RecyclerView.Adapter<MessageHolder>() {
        private var items: List<SignalMessage> = emptyList()

        fun submit(data: List<SignalMessage>) {
            items = data.toList()
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
            b.body.text = m.body
            b.body.setVisible(m.body.isNotEmpty())
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

            bindAttachment(m)
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
            thread(isDaemon = true) {
                val bytes = signalRepo.loadAttachment(id)
                val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                if (bmp != null) imageCache.put(id, bmp)
                runOnUiThread {
                    if (b.image.tag != id) return@runOnUiThread
                    if (bmp == null) {
                        b.attachment.text = getString(R.string.signal_attachment_unavailable)
                        return@runOnUiThread
                    }
                    b.image.setImageBitmap(bmp)
                    b.image.setVisible(true)
                    b.attachment.setVisible(false)
                }
            }
        }
    }

    /** Small: a handful of pictures in view at once, and e-ink shows few at a time. */
    private val imageCache = LruCache<String, android.graphics.Bitmap>(8)
}
