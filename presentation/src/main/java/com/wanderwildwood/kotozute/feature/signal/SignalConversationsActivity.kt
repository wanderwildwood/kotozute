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
        supportActionBar?.title = getString(R.string.signal_title)

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
            val when_ = if (t.lastTs > 0) dateFormatter.getConversationTimestamp(t.lastTs) else ""
            b.subtitle.text = when {
                t.unread > 0 -> "$when_  ·  ${t.unread} unread"
                else -> when_
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
