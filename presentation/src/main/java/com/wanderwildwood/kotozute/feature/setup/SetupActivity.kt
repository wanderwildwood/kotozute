package com.wanderwildwood.kotozute.feature.setup

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.Navigator
import com.wanderwildwood.kotozute.common.base.QkThemedActivity
import com.wanderwildwood.kotozute.common.widget.QkTextView
import com.wanderwildwood.kotozute.databinding.SetupActivityBinding
import com.wanderwildwood.kotozute.feature.desktopsync.DesktopSyncService
import com.wanderwildwood.kotozute.feature.main.DuraSpeed
import com.wanderwildwood.kotozute.feature.signal.SignalLinkActivity
import com.wanderwildwood.kotozute.feature.signal.SignalRegisterActivity
import com.wanderwildwood.kotozute.feature.signal.SignalStreamService
import com.wanderwildwood.kotozute.feature.signal.SignalWording
import com.wanderwildwood.kotozute.feature.signal.say
import com.wanderwildwood.kotozute.manager.PermissionManager
import com.wanderwildwood.kotozute.repository.SignalRepository
import dagger.android.AndroidInjection
import javax.inject.Inject
import kotlin.concurrent.thread

/**
 * The first-run guide: one question to a page, each skippable, each answered the same way
 * Settings would answer it, so nothing here is a second way of doing anything.
 *
 * Pages that do not apply are passed over as they come up rather than decided at the start:
 * whether the Signal pages apply is only known once the Signal page has been answered, and
 * linking happens on its own screen, so the guide asks again each time it comes back.
 */
class SetupActivity : QkThemedActivity() {

    @Inject lateinit var navigator: Navigator
    @Inject lateinit var permissions: PermissionManager
    @Inject lateinit var signalRepo: SignalRepository

    private lateinit var binding: SetupActivityBinding

    private enum class Page { WELCOME, TEXTING, KOMPAKT, SIGNAL, CONTACTS, LISTS, CONNECTED, DESKTOP, DONE }

    private var page = Page.WELCOME

    /**
     * Pages already put in front of the reader. Kept in the count after they stop applying --
     * the texting page once Messaging is the texting app -- or "1 of 4" would become "1 of 3"
     * on the next page, and the reader would be told they had not moved.
     */
    private val shown = mutableSetOf<Page>()

    /** A page's answer has been given and is being carried out; its choices wait. */
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        binding = SetupActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.setup_title)
        page = savedInstanceState?.getString(STATE_PAGE)?.let { runCatching { Page.valueOf(it) }.getOrNull() }
            ?: Page.WELCOME
        savedInstanceState?.getStringArrayList(STATE_SHOWN)?.mapNotNullTo(shown) { runCatching { Page.valueOf(it) }.getOrNull() }
        binding.skip.setOnClickListener { if (!busy) skip() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PAGE, page.name)
        outState.putStringArrayList(STATE_SHOWN, ArrayList(shown.map { it.name }))
    }

    /** Coming back from the texting-app question, or from linking: ask again where things stand. */
    override fun onResume() {
        super.onResume()
        when {
            page == Page.TEXTING && permissions.isDefaultSms() -> next()
            page == Page.SIGNAL && signalOn() -> next()
            else -> show()
        }
    }

    override fun onBackPressed() {
        // Back is Skip: there is no page behind this one worth going back to that Settings
        // does not also hold.
        if (!busy) skip()
    }

    private fun signalOn(): Boolean = runCatching { signalRepo.connectionState().blockingFirst().configured }
        .getOrDefault(false)

    /** Whether a page has anything to ask on this phone, right now. */
    private fun applies(p: Page): Boolean = when (p) {
        Page.TEXTING -> !permissions.isDefaultSms()
        // DuraSpeed is MediaTek's, and on the Kompakt nothing on the phone can switch it off.
        // Whether it is on cannot be read from here, so the page is shown on every Kompakt.
        // Settings has no way in; the button opens its App info, whose Open button does.
        Page.KOMPAKT -> DuraSpeed.isKompakt()
        // Already on Signal: nothing to link. Its own pages follow instead.
        Page.SIGNAL -> !signalOn()
        Page.CONTACTS, Page.LISTS, Page.CONNECTED -> signalOn()
        else -> true
    }

    private fun next() {
        busy = false
        val all = Page.values()
        var i = page.ordinal + 1
        while (i < all.size && !applies(all[i])) i++
        page = all.getOrElse(i) { Page.DONE }
        show()
    }

    private fun skip() {
        if (page == Page.WELCOME || page == Page.DONE) finishGuide() else next()
    }

    private fun finishGuide() {
        prefs.setupSeen.set(true)
        finish()
    }

    private fun show() {
        shown += page
        val pages = Page.values().filter { it != Page.WELCOME && it != Page.DONE && (it in shown || applies(it)) }
        val at = pages.indexOf(page)
        binding.where.isVisible = at >= 0
        binding.where.text = if (at >= 0) getString(R.string.setup_where, at + 1, pages.size) else ""
        binding.status.isVisible = false
        // The last page has its own button; Close under it would be a second way to say it.
        binding.skip.isVisible = page != Page.DONE
        binding.choices.removeAllViews()
        binding.skip.setText(
            if (page == Page.WELCOME) R.string.setup_skip_all else R.string.setup_skip
        )
        when (page) {
            Page.WELCOME -> ask(R.string.setup_welcome_heading, R.string.setup_welcome_body,
                choice(R.string.setup_welcome_start, primary = true) { next() })

            Page.TEXTING -> ask(R.string.setup_texting_heading, R.string.setup_texting_body,
                choice(R.string.setup_texting_make, primary = true) { navigator.showDefaultSmsDialog(this) })

            Page.KOMPAKT -> ask(R.string.setup_kompakt_heading, R.string.setup_kompakt_body,
                choice(R.string.setup_kompakt_how, primary = true) { DuraSpeed.open(this) },
                choice(R.string.duraspeed_allowed) { DuraSpeed.markAllowed(this, prefs); next() })

            Page.SIGNAL -> ask(R.string.setup_signal_heading, R.string.setup_signal_body,
                choice(R.string.setup_signal_link, primary = true) { startActivity(SignalLinkActivity.intent(this)) },
                choice(R.string.setup_signal_register) { startActivity(SignalRegisterActivity.intent(this)) })

            Page.CONTACTS -> ask(R.string.setup_contacts_heading, R.string.setup_contacts_body,
                choice(R.string.setup_contacts_look, primary = true) { lookUpContacts() })

            Page.LISTS -> ask(R.string.setup_lists_heading, R.string.setup_lists_body,
                choice(R.string.setup_lists_one, primary = prefs.signalWeave.get()) { prefs.signalWeave.set(true); next() },
                choice(R.string.setup_lists_two, primary = !prefs.signalWeave.get()) { prefs.signalWeave.set(false); next() })

            Page.CONNECTED -> ask(R.string.setup_connected_heading, R.string.setup_connected_body,
                choice(R.string.setup_connected_keep) { keepConnected(true) },
                choice(R.string.setup_connected_catch_up, primary = true) { keepConnected(false) })

            Page.DESKTOP -> ask(R.string.setup_desktop_heading, R.string.setup_desktop_body,
                choice(R.string.setup_desktop_on) {
                    DesktopSyncService.start(this)
                    tell(getString(R.string.setup_desktop_started))
                    showOnly(choice(R.string.setup_continue, primary = true) { next() })
                })

            Page.DONE -> ask(R.string.setup_done_heading, R.string.setup_done_body,
                choice(R.string.setup_done_finish, primary = true) { finishGuide() })
        }
    }

    private fun ask(heading: Int, body: Int, vararg choices: QkTextView) {
        binding.heading.setText(heading)
        binding.body.setText(body)
        choices.forEach { binding.choices.addView(it) }
    }

    private fun showOnly(vararg choices: QkTextView) {
        binding.choices.removeAllViews()
        choices.forEach { binding.choices.addView(it) }
    }

    private fun tell(text: String) {
        binding.status.text = text
        binding.status.isVisible = true
    }

    private fun choice(label: Int, primary: Boolean = false, onChoose: () -> Unit) =
        choice(getString(label), primary, onChoose)

    /** A choice drawn as MMD draws a button: filled for the one suggested, outlined for the rest. */
    private fun choice(label: String, primary: Boolean = false, onChoose: () -> Unit) = QkTextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 17f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(if (primary) Color.WHITE else Color.BLACK)
        setBackgroundResource(if (primary) R.drawable.setup_option_primary else R.drawable.setup_option)
        val pad = (14 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = (10 * resources.displayMetrics.density).toInt() }
        setOnClickListener { if (!busy) onChoose() }
    }

    /**
     * The account's own contact list, then Signal's lookup of the address book: the second is
     * what pairs a person's texts with their Signal conversation. Both are what Settings runs.
     */
    private fun lookUpContacts() {
        busy = true
        tell(getString(R.string.setup_contacts_looking))
        binding.choices.removeAllViews()
        thread(isDaemon = true) {
            if (signalRepo.shouldOfferContactFetch()) runCatching { signalRepo.fetchContactsFromSignal() }
            val words = runCatching { say(SignalWording.contacts(signalRepo.discoverContactsByNumber())) }
                .getOrElse { getString(R.string.settings_signal_discover_contacts_failed) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                busy = false
                tell(words)
                showOnly(choice(R.string.setup_continue, primary = true) { next() })
            }
        }
    }

    private fun keepConnected(on: Boolean) {
        prefs.signalKeepConnected.set(on)
        SignalStreamService.sync(this, on)
        next()
    }

    companion object {
        private const val STATE_PAGE = "page"
        private const val STATE_SHOWN = "shown"

        fun intent(context: Context) = Intent(context, SetupActivity::class.java)
    }
}
