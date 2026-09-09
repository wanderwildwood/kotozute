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

    /** Carried rather than kept in the repository, so abandoning leaves nothing behind. */
    private var sessionId: String? = null
    private var number: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        binding = SignalRegisterActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.signal_register_title)
        showBackButton(true)

        binding.send.setOnClickListener {
            number = binding.number.text.toString().trim()
            step(R.string.signal_register_working) { signalRepo.registerBegin(number) }
        }

        binding.verify.setOnClickListener {
            val id = sessionId ?: return@setOnClickListener
            val code = binding.code.text.toString().trim()
            step(R.string.signal_register_working) { signalRepo.registerVerify(id, code, number) }
        }

        binding.callInstead.setOnClickListener {
            val id = sessionId ?: return@setOnClickListener
            step(R.string.signal_register_working) { signalRepo.registerResend(id, voice = true) }
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
            binding.numberStep.setVisible(false)
            binding.codeStep.setVisible(true)
            binding.status.setText(R.string.signal_register_code_sent)
        }

        is SignalRepository.Registration.Registered -> {
            binding.captcha.setVisible(false)
            binding.numberStep.setVisible(false)
            binding.codeStep.setVisible(false)
            binding.status.setText(R.string.signal_register_done)
        }

        is SignalRepository.Registration.Failed ->
            binding.status.text = getString(R.string.signal_register_failed, result.reason)
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
        binding.captcha.setVisible(true)
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
        binding.captcha.loadUrl(CAPTCHA_URL)
    }

    private fun onCaptchaSolved(token: String) {
        val id = sessionId ?: return
        binding.captcha.setVisible(false)
        // The token is single-use and short-lived, and it is not ours to keep: it goes
        // straight back to the server that issued the challenge, and is never logged.
        step(R.string.signal_register_working) { signalRepo.registerCaptcha(id, token) }
    }

    companion object {
        private const val CAPTCHA_HOST = "signalcaptchas.org"
        private const val CAPTCHA_URL = "https://$CAPTCHA_HOST/registration/generate.html"
        private const val CAPTCHA_SCHEME = "signalcaptcha://"

        fun intent(context: Context) = Intent(context, SignalRegisterActivity::class.java)
    }
}
