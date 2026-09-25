package com.wanderwildwood.kotozute.feature.signal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.result.contract.ActivityResultContracts
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.base.QkThemedActivity
import com.wanderwildwood.kotozute.common.util.extensions.resolveThemeColor
import com.wanderwildwood.kotozute.common.util.extensions.setVisible
import com.wanderwildwood.kotozute.databinding.SignalConversationsActivityBinding
import com.wanderwildwood.kotozute.databinding.SignalThreadListItemBinding
import com.wanderwildwood.kotozute.feature.contacts.ContactsActivity
import com.wanderwildwood.kotozute.model.SignalThread
import com.wanderwildwood.kotozute.repository.SignalRepository
import com.wanderwildwood.kotozute.common.util.DateFormatter
import dagger.android.AndroidInjection
import io.reactivex.disposables.CompositeDisposable
import io.realm.RealmResults
import javax.inject.Inject
import android.view.Menu
import android.view.MenuItem
import com.wanderwildwood.kotozute.common.util.extensions.turnsAPageOnSwipe
import com.wanderwildwood.kotozute.common.util.extensions.stopAnimatingItems

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
    private lateinit var adapter: ThreadAdapter
    private val disposables = CompositeDisposable()
    private var threads: RealmResults<SignalThread>? = null

    /** Which shelf is on screen. Archiving with no way back to the thread would lose it. */
    private var showingArchived = false
    private val openedAsHome by lazy { intent.getBooleanExtra(EXTRA_AS_HOME, false) }

    /**
     * Picking somebody to write to, or making a group. Both hand back a thread key rather
     * than opening the conversation themselves, the same as the picker does for the SMS
     * composer -- so the screen that asked decides what happens next, and here that is
     * opening the conversation with this list behind it.
     */
    private val newConversation = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val threadKey = data.getStringExtra(ContactsActivity.SIGNAL_THREAD_KEY).orEmpty()
        if (threadKey.isBlank()) return@registerForActivityResult
        val title = data.getStringExtra(ContactsActivity.SIGNAL_THREAD_TITLE).orEmpty()
        startActivity(intentFor(this, threadKey, title))
    }

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
        binding.railBadge.setOnClickListener {
            navigator.crossToSmsList()
            finish()
        }
        binding.filterAll.setOnClickListener { chooseFilter(FILTER_ALL) }
        binding.filterGroups.setOnClickListener { chooseFilter(FILTER_GROUPS) }

        adapter = ThreadAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        // The Signal list turns a page like the SMS one. Both rails are the same app on the
        // same panel, and a list that scrolls freely next to one that does not is the kind of
        // difference a person feels without being able to name.
        binding.recyclerView.turnsAPageOnSwipe()

        bindShelf()

        disposables += signalRepo.connectionState()
            .subscribe { conn ->
                // Three states, not two: connected, on its way, and not going to happen.
                // The middle one used to read as the last one.
                val msg = when {
                    conn.signalConnected -> null
                    conn.connecting -> getString(R.string.signal_connecting_signal)
                    else -> getString(R.string.signal_cannot_send_signal)
                }
                runOnUiThread {
                    binding.status.text = msg.orEmpty()
                    binding.status.setVisible(msg != null)
                }
            }

        // Writing to somebody there is no conversation with yet. The address book this
        // opens is the Signal one; reaching it used to mean crossing to the SMS inbox,
        // opening its composer, and crossing back.
        binding.compose.setOnClickListener {
            newConversation.launch(
                Intent(this, ContactsActivity::class.java)
                    .putExtra(ContactsActivity.SIGNAL_KEY, true)
            )
        }

        // Catch up on anything missed while the app was closed.
        Thread { signalRepo.syncNow() }.also { it.isDaemon = true }.start()
        signalRepo.startStream()
    }

    /**
     * Point the list at one shelf or the other. Both live in the same screen because a
     * separate archive activity would need its own copy of everything here, and the only
     * difference is which threads the query returns.
     */
    private fun bindShelf() {
        threads?.removeAllChangeListeners()
        val results = signalRepo.getThreads(showingArchived)
        threads = results
        binding.empty.text = getString(
            if (showingArchived) R.string.signal_archived_empty else R.string.signal_empty
        )
        binding.toolbarTitle.text = getString(
            if (showingArchived) R.string.signal_title_archived else R.string.signal_title
        )
        // The crossing is to the SMS inbox; from the archive shelf the way out is the shelf
        // toggle, not a jump to another rail's inbox.
        val separate = !prefs.signalWeave.get() && !showingArchived
        binding.railBadge.setVisible(separate)
        binding.filterTabs.setVisible(separate)
        binding.toolbarTitle.setVisible(!separate)
        selectFilterTab()
        // Opened as the first screen there is nothing to go up to; the archive shelf still
        // has its way back to the inbox.
        supportActionBar?.setDisplayHomeAsUpEnabled(showingArchived || !openedAsHome)
        // After the tabs are shown or hidden: whether the tab applies depends on it.
        results.addChangeListener { data, _ -> show(data) }
        show(results)
        // Nothing is started from the archive shelf: a new conversation would appear on the
        // inbox shelf, behind the screen that was showing.
        binding.compose.setVisible(!showingArchived)
        invalidateOptionsMenu()
    }

    /** The tab applies only where the tabs are showing, so the archive shelf is never short. */
    private fun show(data: List<SignalThread>) {
        val filtered = binding.filterTabs.visibility == View.VISIBLE &&
            prefs.signalConversationFilter.get() == FILTER_GROUPS
        val shown = if (filtered) data.filter { it.kind == "group" } else data
        adapter.submit(shown)
        val empty = shown.isEmpty()
        binding.empty.setVisible(empty)
        binding.recyclerView.setVisible(!empty)
    }

    private fun chooseFilter(filter: Int) {
        prefs.signalConversationFilter.set(filter)
        selectFilterTab()
        threads?.let(::show)
    }

    private fun selectFilterTab() {
        val groups = prefs.signalConversationFilter.get() == FILTER_GROUPS
        listOf(binding.filterAll to !groups, binding.filterGroups to groups)
            .forEach { (tab, selected) ->
                tab.setBackgroundResource(
                    if (selected) R.drawable.filter_tab_selected else android.R.color.transparent
                )
                tab.setTextColor(if (selected) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
            }
    }

    override fun onResume() {
        super.onResume()
        // The same one-time ask as the text list, for a phone that opens here instead.
        BackgroundRunning.askOnce(this, prefs)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.signal_conversations, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        // Nothing is started from the archive shelf, the same reason the compose button is
        // not offered there.
        menu?.findItem(R.id.signalNewGroup)?.isVisible = !showingArchived
        menu?.findItem(R.id.signalArchivedShelf)?.setTitle(
            if (showingArchived) R.string.signal_inbox_shelf else R.string.signal_archived_shelf
        )
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.signalNewGroup -> {
            newConversation.launch(SignalNewGroupActivity.intentFor(this))
            true
        }
        R.id.signalArchivedShelf -> {
            showingArchived = !showingArchived
            bindShelf()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        // Back out of the archive shelf before leaving the screen, so the way in has a way
        // out that does not need the menu.
        if (showingArchived) {
            showingArchived = false
            bindShelf()
            return true
        }
        finish()
        return true
    }

    override fun onBackPressed() {
        if (showingArchived) {
            showingArchived = false
            bindShelf()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onDestroy() {
        threads?.removeAllChangeListeners()
        disposables.clear()
        super.onDestroy()
    }

    private inner class ThreadAdapter : RecyclerView.Adapter<ThreadHolder>() {
        override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
            super.onAttachedToRecyclerView(recyclerView)
            recyclerView.stopAnimatingItems()
        }

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
            bindUnread(t.unread > 0)
        }

        /**
         * Unread reads the way it does on the merged list: bold dark snippet up to five lines,
         * bold time, and the dot. The name is bold either way, there as here. Both states are set every time, because a holder
         * that drew an unread thread is recycled onto a read one.
         */
        private fun bindUnread(unread: Boolean) {
            val style = if (unread) Typeface.BOLD else Typeface.NORMAL
            // ⚠ Typeface.create keeps the font the styler chose; `setTypeface(null, …)` would
            // drop it, and `setTypeface(tf, NORMAL)` cannot take bold back off.
            listOf(b.subtitle, b.timestamp).forEach { it.typeface = Typeface.create(it.typeface, style) }
            b.subtitle.setTextColor(
                resolveThemeColor(
                    if (unread) android.R.attr.textColorPrimary
                    else android.R.attr.textColorSecondary
                )
            )
            b.subtitle.maxLines = if (unread) 5 else 1
            b.unread.setVisible(unread)
        }
    }

    companion object {
        const val EXTRA_AS_HOME = "asHome"
        private const val FILTER_ALL = 0
        private const val FILTER_GROUPS = 1
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
