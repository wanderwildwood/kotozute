package com.wanderwildwood.kotozute.feature.signal

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
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

class SignalThreadActivity : QkThemedActivity() {

    @Inject lateinit var signalRepo: SignalRepository
    @Inject lateinit var dateFormatter: DateFormatter
    @Inject lateinit var notifications: SignalNotifications

    private lateinit var binding: SignalThreadActivityBinding
    private val disposables = CompositeDisposable()
    private var messages: RealmResults<SignalMessage>? = null
    private lateinit var threadKey: String

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        binding = SignalThreadActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        threadKey = SignalConversationsActivity.threadKeyOf(intent)
        if (threadKey.isBlank()) { finish(); return }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = SignalConversationsActivity.titleOf(intent).ifBlank { getString(R.string.signal_title) }

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
    }

    private fun markRead(data: List<SignalMessage>) {
        val newest = data.maxOfOrNull { it.date } ?: return
        if (data.any { !it.outgoing && !it.read }) signalRepo.markRead(threadKey, newest)
    }

    private fun send() {
        val body = binding.message.text?.toString().orEmpty().trim()
        if (body.isEmpty()) return
        binding.send.isEnabled = false
        thread(isDaemon = true) {
            val result = runCatching { signalRepo.send(threadKey, body) }
            runOnUiThread {
                binding.send.isEnabled = true
                result
                    .onSuccess { binding.message.setText("") }
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
            holder.bind(items[position])
    }

    private inner class MessageHolder(
        private val b: SignalMessageListItemBinding
    ) : RecyclerView.ViewHolder(b.root) {
        fun bind(m: SignalMessage) {
            b.body.text = m.body
            b.body.setVisible(m.body.isNotEmpty())
            b.timestamp.text = dateFormatter.getMessageTimestamp(m.date)
            val label = when {
                m.outgoing -> getString(R.string.signal_you)
                m.senderNumber.isNotBlank() -> m.senderNumber
                else -> ""
            }
            b.sender.text = label
            b.sender.setVisible(label.isNotEmpty())
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
            if (id.isBlank()) return

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
