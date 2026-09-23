package com.wanderwildwood.kotozute.feature.signal

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.base.QkThemedActivity
import com.wanderwildwood.kotozute.common.util.extensions.setVisible
import com.wanderwildwood.kotozute.databinding.SignalRegisterActivityBinding
import com.wanderwildwood.kotozute.repository.SignalRepository
import dagger.android.AndroidInjection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Registering this phone's number as its own Signal account.
 *
 * The other way in is [SignalLinkActivity], which is right for almost everyone. This is for
 * the person with no other phone to link to — installing Signal for the first time, through
 * this app.
 *
 * The screen leads with what it costs, before anything is typed, because the cost is not
 * recoverable by tapping back: Signal allows one primary device per number, so registering
 * here deregisters that number's Signal wherever else it is and drops its linked devices.
 *
 * Steps are shown and hidden in one screen rather than pushed. The flow genuinely goes
 * backwards — a captcha can be demanded again, a code can be resent or asked for by voice —
 * and a back stack that has to be unwound to allow that is a worse machine than a visibility.
 */
class SignalRegisterActivity : QkThemedActivity() {

    @Inject lateinit var signalRepo: SignalRepository

    private lateinit var binding: SignalRegisterActivityBinding

    /**
     * Carried rather than kept in the repository, so abandoning leaves nothing behind.
     *
     * In memory only, which means it does **not** survive the activity being destroyed: a
     * rebuilt screen starts a new session and a new captcha. That is the honest limit of this
     * -- an earlier version of this comment claimed the opposite.
     */
    private var sessionId: String? = null
    private var number: String = ""

    /**
     * Which way the last code was asked for.
     *
     * The screen said "Code sent" either way, so asking to be called looked exactly like
     * doing nothing -- and on a number that cannot receive short-code texts, which is the
     * whole reason the voice option exists, that is the moment someone most needs to be told
     * something is happening.
     */
    private var askedForACall = false

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        binding = SignalRegisterActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.signal_register_title)
        showBackButton(true)

        binding.send.setOnClickListener {
            askedForACall = false
            number = binding.number.text.toString().trim()
            step(R.string.signal_register_working) { signalRepo.registerBegin(number) }
        }

        binding.verify.setOnClickListener {
            val id = sessionId ?: return@setOnClickListener
            val code = binding.code.text.toString().trim()
            step(R.string.signal_register_working) { signalRepo.registerVerify(id, code, number) }
        }

        binding.callInstead.setOnClickListener {
            askedForACall = true
            val id = sessionId ?: return@setOnClickListener
            step(R.string.signal_register_working) { signalRepo.registerResend(id, voice = true) }
        }

        binding.saveName.setOnClickListener { saveName() }

        showStagingSwitchIfDebug()
    }

    /**
     * The staging switch, on debug builds only.
     *
     * ⚠ It does **not** take effect until the process restarts, and the row says so rather
     * than pretending otherwise. The environment decides which servers everything uses and is
     * read once at startup, before Dagger builds the stores that keep a configuration -- so
     * flipping it under a running app would move some things and not others, which is the
     * incoherent state `SignalNetworkConfig.environment` exists to prevent.
     *
     * Living on this screen rather than in Settings is deliberate: this is the only flow that
     * has any use for it, and a release build never shows it at all.
     */
    private fun showStagingSwitchIfDebug() {
        if (!com.wanderwildwood.kotozute.BuildConfig.DEBUG) return

        @Suppress("DEPRECATION")
        val prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(this)
        binding.staging.setVisible(true)
        binding.staging.isChecked = prefs.getBoolean(STAGING_PREF, false)
        binding.staging.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(STAGING_PREF, checked).apply()
            if (checked) binding.status.setText(R.string.signal_register_staging_on)
        }
    }

    /**
     * Sends the profile name, the last step of registering.
     *
     * ⚠ Not routed through [step], and not a [SignalRepository.Registration] either. By the
     * time this runs the account **already exists** -- it was created by the call before this
     * one -- so a failure here is not a failure to register, and rendering it as one would
     * tell somebody their account did not happen when it did, and invite them to start over
     * against a number that is now taken by this very phone. It fails as itself: the account
     * stands, the name did not save, and the button can simply be pressed again.
     */
    private fun saveName() {
        val given = binding.givenName.text.toString().trim()
        val family = binding.familyName.text.toString().trim()
        // Signal's own rule, from `ProfileName.serialize`: no given name is no name at all,
        // whatever the family field says. Refused here so the trip is not wasted.
        if (given.isEmpty()) {
            binding.status.setText(R.string.signal_register_name_hint)
            return
        }

        binding.status.setText(R.string.signal_register_naming)
        binding.saveName.isEnabled = false
        CoroutineScope(Dispatchers.IO).launch {
            val failure = signalRepo.registerSetProfileName(given, family)
            withContext(Dispatchers.Main) {
                binding.saveName.isEnabled = true
                if (failure == null) {
                    binding.nameStep.setVisible(false)
                    binding.status.setText(R.string.signal_register_named)
                } else {
                    binding.status.text = getString(
                        R.string.signal_register_name_failed,
                        say(SignalWording.profileName(failure))
                    )
                }
            }
        }
    }

    /**
     * Runs one step off the main thread and renders whatever comes back.
     *
     * Every button goes through here so that no path can leave the screen showing the previous
     * step's message while a request is in flight — which on a panel that redraws slowly reads
     * as the tap not having registered, and invites a second one.
     */
    private fun step(working: Int, body: suspend () -> SignalRepository.Registration) {
        binding.status.setText(working)
        setButtonsEnabled(false)
        CoroutineScope(Dispatchers.IO).launch {
            val result = body()
            withContext(Dispatchers.Main) {
                setButtonsEnabled(true)
                render(result)
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.send.isEnabled = enabled
        binding.verify.isEnabled = enabled
        binding.callInstead.isEnabled = enabled
    }

    private fun render(result: SignalRepository.Registration) = when (result) {
        is SignalRepository.Registration.NeedsCaptcha -> {
            sessionId = result.sessionId
            binding.status.setText(R.string.signal_register_captcha)
            showCaptcha()
        }


        is SignalRepository.Registration.CodeSent -> {
            sessionId = result.sessionId
            binding.captcha.setVisible(false)
            binding.warning.setVisible(false)
            binding.numberStep.setVisible(false)
            binding.codeStep.setVisible(true)
            binding.scroll.post { binding.scroll.scrollTo(0, 0) }
            binding.status.setText(
                if (askedForACall) R.string.signal_register_calling
                else R.string.signal_register_code_sent
            )
        }

        is SignalRepository.Registration.Registered -> {
            binding.captcha.setVisible(false)
            // The warning is about taking a number over, which has now happened. Leaving it
            // above the name step would push that step down the same way the captcha was.
            binding.warning.setVisible(false)
            binding.numberStep.setVisible(false)
            binding.codeStep.setVisible(false)
            // The account exists from here. The name is the one thing still missing, and it
            // is asked for now rather than left to a settings screen nobody would think to
            // visit: an account with no profile name is not obviously broken from the inside,
            // only from the outside, where every correspondent sees a bare number.
            binding.nameStep.setVisible(true)
            binding.scroll.post { binding.scroll.scrollTo(0, 0) }
            binding.status.setText(R.string.signal_register_done)
        }

        is SignalRepository.Registration.Failed -> {
            // ⚠ Puts the number step back. [showCaptcha] hides it, so without this a refused
            // captcha or a rejected number left the screen with a message and no way to try
            // again -- the dead end that hiding it would otherwise create.
            binding.captcha.setVisible(false)
            binding.warning.setVisible(true)
            binding.numberStep.setVisible(true)
            binding.scroll.post { binding.scroll.scrollTo(0, 0) }
            binding.status.text = getString(
                R.string.signal_register_failed,
                say(SignalWording.registration(result.failure))
            )
        }
    }

    /**
     * Signal's own captcha, in a web view.
     *
     * It has to be a web page: the challenge is served and scored by Signal, and nothing here
     * can solve or forge one. All this does is watch for the `signalcaptcha://` URL the page
     * finishes on and take the token out of it.
     *
     * JavaScript is on because the page is a captcha and does not work without it. Nothing
     * else is granted: no file access, no storage, and no other origin is followed — a
     * navigation away from Signal's own host is refused rather than loaded, so a redirect
     * cannot turn this into a general browser pointed wherever a page fancies.
     */
    @Suppress("SetJavaScriptEnabled")
    private fun showCaptcha() {
        // ⚠ **The step above has to go, or the captcha lands below the fold.**
        //
        // This screen is one scrolling column and the captcha sits under the number step, so
        // showing it without hiding anything left it starting at y=622 on an 800px panel: a
        // ~180px sliver at the very bottom, with the line telling somebody to solve it scrolled
        // off entirely. Measured on the emulator at the Kompakt's own 480x800.
        //
        // What that looks like from the outside is a button that does nothing. The request had
        // gone, the server had answered, the WebView had loaded the challenge -- and the screen
        // still showed a number field and "Send me a code", so the natural thing to do was press
        // it again.
        //
        // There is nothing else to do at this moment, so nothing else is shown: the warning has
        // been read, and the number cannot be changed without a new session anyway.
        binding.warning.setVisible(false)
        binding.numberStep.setVisible(false)
        binding.captcha.setVisible(true)
        // A column that was scrolled down stays scrolled down when its contents change.
        binding.scroll.post { binding.scroll.scrollTo(0, 0) }
        binding.captcha.settings.javaScriptEnabled = true
        binding.captcha.settings.allowFileAccess = false
        binding.captcha.settings.allowContentAccess = false
        binding.captcha.settings.domStorageEnabled = false

        binding.captcha.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString().orEmpty()
                if (url.startsWith(CAPTCHA_SCHEME)) {
                    onCaptchaSolved(url.removePrefix(CAPTCHA_SCHEME))
                    return true
                }
                // Only Signal's own captcha host is followed. Anything else is not part of
                // this exchange and has no business loading inside it.
                return request?.url?.host != CAPTCHA_HOST
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url != null && url.startsWith(CAPTCHA_SCHEME)) {
                    view?.stopLoading()
                    onCaptchaSolved(url.removePrefix(CAPTCHA_SCHEME))
                }
            }
        }
        // ⚠ Follows the environment. A production captcha token is refused by staging and
        // the reverse is true too, and the refusal reads as "solve it again" -- which never
        // succeeds, however many times it is tried.
        binding.captcha.loadUrl(
            com.wanderwildwood.kotozute.signalstore.SignalNetworkConfig.currentCaptchaUrl()
        )
    }

    private fun onCaptchaSolved(token: String) {
        val id = sessionId ?: return
        binding.captcha.setVisible(false)
        // The token is single-use and short-lived, and it is not ours to keep: it goes
        // straight back to the server that issued the challenge, and is never logged.
        step(R.string.signal_register_working) { signalRepo.registerCaptcha(id, token) }
    }

    companion object {
        /** Read by [com.wanderwildwood.kotozute.common.QKApplication] at startup. */
        private const val STAGING_PREF = "signalStaging"

        private const val CAPTCHA_HOST = "signalcaptchas.org"
        private const val CAPTCHA_SCHEME = "signalcaptcha://"

        fun intent(context: Context) = Intent(context, SignalRegisterActivity::class.java)
    }
}
