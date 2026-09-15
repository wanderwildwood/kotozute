package com.wanderwildwood.kotozute.feature.signal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.base.QkThemedActivity
import com.wanderwildwood.kotozute.common.util.extensions.setVisible
import com.wanderwildwood.kotozute.common.util.extensions.turnsAPageOnSwipe
import com.wanderwildwood.kotozute.databinding.SignalGroupMemberListItemBinding
import com.wanderwildwood.kotozute.databinding.SignalNewGroupActivityBinding
import com.wanderwildwood.kotozute.extensions.removeAccents
import com.wanderwildwood.kotozute.feature.contacts.ContactsActivity
import com.wanderwildwood.kotozute.feature.contacts.matches
import com.wanderwildwood.kotozute.repository.SignalRepository
import dagger.android.AndroidInjection
import timber.log.Timber
import javax.inject.Inject

/**
 * Making a group: who is in it, and what it is called.
 *
 * Its own screen rather than a mode of the contact picker, which is how Signal separates
 * them too -- `CreateGroupActivity` is not `NewConversationActivity`. Picking one person to
 * write to and picking several to build something out of are different acts, and the picker
 * answers to the SMS composer besides.
 *
 * One screen, where Signal uses two: its second step carries an avatar, a disappearing
 * timer and the members again, and this carries a name. A whole screen to type one word is
 * a full repaint for nothing on a panel like this one.
 */
class SignalNewGroupActivity : QkThemedActivity() {

    @Inject lateinit var signalRepo: SignalRepository

    private lateinit var binding: SignalNewGroupActivityBinding
    private lateinit var adapter: PeopleAdapter

    /** Everyone who could be in it. Read once: the directory does not move while typing. */
    private var people: List<SignalRepository.Person> = emptyList()

    /** Who is in it so far, by thread key. Order is the order they were chosen. */
    private val chosen = linkedSetOf<String>()

    /** Set while the group is being made, so a second tap cannot make a second group. */
    private var creating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        binding = SignalNewGroupActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.signal_group_title)

        adapter = PeopleAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.turnsAPageOnSwipe()

        binding.search.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) = show()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            }
        )

        // Off the main thread: reading who is on Signal opens the keystore and the encrypted
        // store behind it.
        Thread {
            val read = runCatching { signalRepo.people() }
                .onFailure { Timber.w(it, "signal groups: could not read who is on Signal") }
                .getOrDefault(emptyList())
            runOnUiThread {
                people = read
                show()
            }
        }.also { it.isDaemon = true }.start()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.signal_new_group, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.create -> {
            create()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /** The list, filtered by what has been typed. */
    private fun show() {
        val query = binding.search.text?.toString().orEmpty()
        val normalized = query.removeAccents()
        val shown = people.filter { person ->
            query.isBlank() || matches(person.name, person.number, query, normalized)
        }
        adapter.submit(shown)
        binding.empty.setVisible(shown.isEmpty())
        binding.recyclerView.setVisible(shown.isNotEmpty())
        binding.empty.setText(
            if (people.isEmpty()) R.string.signal_group_nobody else R.string.signal_group_no_match
        )
    }

    private fun create() {
        if (creating) return
        val title = binding.name.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) {
            say(getString(R.string.signal_group_needs_a_name))
            return
        }
        if (chosen.isEmpty()) {
            say(getString(R.string.signal_group_needs_somebody))
            return
        }

        creating = true
        val members = chosen.toList()
        Thread {
            val result = runCatching { signalRepo.createGroup(title, members) }
            runOnUiThread {
                creating = false
                result.onSuccess { threadKey ->
                    // Handed back rather than opened here, the same as the picker does with
                    // a person: whoever asked for this decides what stands behind the new
                    // conversation, and the picker has a composer of its own to dismiss.
                    setResult(
                        Activity.RESULT_OK,
                        Intent()
                            .putExtra(ContactsActivity.SIGNAL_THREAD_KEY, threadKey)
                            .putExtra(ContactsActivity.SIGNAL_THREAD_TITLE, title)
                    )
                    finish()
                }
                result.onFailure { error ->
                    Timber.w(error, "signal groups: the group was not made")
                    // Its own words where it has them. Every way this can fail means
                    // something different, and one message for all of them would leave the
                    // reader with nowhere to go.
                    say(error.message ?: getString(R.string.signal_group_not_made))
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun say(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private inner class PeopleAdapter : RecyclerView.Adapter<PersonHolder>() {
        private var items: List<SignalRepository.Person> = emptyList()

        fun submit(data: List<SignalRepository.Person>) {
            items = data
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PersonHolder =
            PersonHolder(
                SignalGroupMemberListItemBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: PersonHolder, position: Int) {
            val person = items[position]
            holder.bind(person)
            holder.itemView.setOnClickListener {
                if (!chosen.remove(person.threadKey)) chosen.add(person.threadKey)
                notifyItemChanged(position)
            }
        }
    }

    private inner class PersonHolder(
        private val b: SignalGroupMemberListItemBinding
    ) : RecyclerView.ViewHolder(b.root) {

        fun bind(person: SignalRepository.Person) {
            b.title.text = person.name
            // Not when the row's name is already the number: the same fact twice.
            b.subtitle.setVisible(person.number.isNotBlank() && person.number != person.name)
            b.subtitle.text = person.number
            // Solid for chosen, nothing for not -- the border says it, as it does elsewhere.
            b.row.setBackgroundResource(
                if (person.threadKey in chosen) R.drawable.row_outline else 0
            )
        }
    }

    companion object {
        fun intentFor(context: Context): Intent =
            Intent(context, SignalNewGroupActivity::class.java)
    }
}
