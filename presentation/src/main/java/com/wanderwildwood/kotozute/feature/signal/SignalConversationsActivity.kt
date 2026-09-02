package com.wanderwildwood.kotozute.feature.signal

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.base.QkThemedActivity
import com.wanderwildwood.kotozute.common.util.extensions.setVisible
import com.wanderwildwood.kotozute.databinding.SignalConversationsActivityBinding
import com.wanderwildwood.kotozute.databinding.SignalThreadListItemBinding
import com.wanderwildwood.kotozute.model.SignalThread
import com.wanderwildwood.kotozute.repository.SignalRepository
import com.wanderwildwood.kotozute.common.util.DateFormatter
import dagger.android.AndroidInjection
import io.reactivex.disposables.CompositeDisposable
import io.realm.RealmResults
import javax.inject.Inject

/**
 * The Signal rail on its own, until Signal threads are interleaved into the main
 * conversation list. Kept entirely separate from that list on purpose: it is backed by
 * different Realm classes and none of the list's actions -- archive, pin, delete,
 * multi-select -- mean anything on a Signal thread yet.
 */
class SignalConversationsActivity : QkThemedActivity() {

    @Inject lateinit var navigator: com.wanderwildwood.kotozute.common.Navigator
    @Inject lateinit var signalRepo: SignalRepository
    @Inject lateinit var dateFormatter: DateFormatter

    private lateinit var binding: SignalConversationsActivityBinding
    private val disposables = CompositeDisposable()
    private var threads: RealmResults<SignalThread>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        binding = SignalConversationsActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // Its own title view, so the rail badge can sit beside it as it does everywhere else.
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.toolbarTitle.text = getString(R.string.signal_title)

        // Only while the two lists are being kept apart. Reached from Settings while they
        // are woven, there is no SMS-only list to go back to and the badge would mislead.
        binding.railBadge.setVisible(!prefs.signalWeave.get())
        binding.railBadge.setOnClickListener { navigator.showMainActivity() }

        val adapter = ThreadAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        val results = signalRepo.getThreads()
        threads = results
        results.addChangeListener { data, _ ->
            adapter.submit(data)
            val empty = data.isEmpty()
            binding.empty.setVisible(empty)
            binding.recyclerView.setVisible(!empty)
        }
        adapter.submit(results)

        disposables += signalRepo.connectionState()
            .subscribe { conn ->
                val msg = when {
                    !conn.bridgeReachable -> getString(R.string.signal_cannot_send_bridge)
                    !conn.signalConnected -> getString(R.string.signal_cannot_send_signal)
                    else -> null
                }
                runOnUiThread {
                    binding.status.text = msg.orEmpty()
                    binding.status.setVisible(msg != null)
                }
            }

        // Catch up on anything missed while the app was closed.
        Thread { signalRepo.syncNow() }.also { it.isDaemon = true }.start()
        signalRepo.startStream()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        threads?.removeAllChangeListeners()
        disposables.clear()
        super.onDestroy()
    }

    private inner class ThreadAdapter : RecyclerView.Adapter<ThreadHolder>() {
        private var items: List<SignalThread> = emptyList()

        fun submit(data: List<SignalThread>) {
            items = data.toList()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThreadHolder =
            ThreadHolder(
                SignalThreadListItemBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ThreadHolder, position: Int) {
            val t = items[position]
            holder.bind(t)
            holder.itemView.setOnClickListener {
                startActivity(intentFor(this@SignalConversationsActivity, t.threadKey, holder.titleOf(t)))
            }
        }
    }

    private inner class ThreadHolder(
        private val b: SignalThreadListItemBinding
    ) : RecyclerView.ViewHolder(b.root) {

        fun titleOf(t: SignalThread): String = when {
            t.title.isNotBlank() -> t.title
            t.counterpartNumber.isNotBlank() -> t.counterpartNumber
            t.kind == "group" -> getString(R.string.signal_title)
            else -> t.threadKey.substringAfter(":")
        }

        fun bind(t: SignalThread) {
            b.title.text = titleOf(t)
            b.timestamp.text =
                if (t.lastTs > 0) dateFormatter.getConversationTimestamp(t.lastTs) else ""
            // The same line the merged list shows, so a thread reads the same wherever it
            // is seen. It used to repeat the timestamp here and show no message at all.
            b.subtitle.text = when {
                t.snippet.isBlank() && t.unread > 0 -> resources.getQuantityString(
                    R.plurals.signal_unread, t.unread, t.unread
                )
                t.snippetOutgoing && t.snippet.isNotBlank() ->
                    getString(R.string.main_sender_you, t.snippet)
                else -> t.snippet
            }
        }
    }

    companion object {
        private const val EXTRA_KEY = "threadKey"
        private const val EXTRA_TITLE = "threadTitle"

        fun intentFor(context: Context, threadKey: String, title: String): Intent =
            Intent(context, SignalThreadActivity::class.java)
                .putExtra(EXTRA_KEY, threadKey)
                .putExtra(EXTRA_TITLE, title)

        fun threadKeyOf(intent: Intent): String = intent.getStringExtra(EXTRA_KEY).orEmpty()
        fun titleOf(intent: Intent): String = intent.getStringExtra(EXTRA_TITLE).orEmpty()
    }
}

private operator fun CompositeDisposable.plusAssign(d: io.reactivex.disposables.Disposable) {
    add(d)
}
