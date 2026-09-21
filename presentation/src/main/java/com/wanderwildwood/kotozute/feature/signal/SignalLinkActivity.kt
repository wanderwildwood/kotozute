package com.wanderwildwood.kotozute.feature.signal

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.base.QkThemedActivity
import com.wanderwildwood.kotozute.databinding.SignalLinkActivityBinding
import com.wanderwildwood.kotozute.repository.SignalRepository
import com.wanderwildwood.kotozute.feature.desktopsync.DesktopSyncService
import com.wanderwildwood.kotozute.feature.desktopsync.SignalLinkOffer
import dagger.android.AndroidInjection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Links this phone to a Signal account by showing a code for another device to scan.
 *
 * The same exchange Molly and Signal Desktop perform, and the point of the whole branch: once
 * it completes there is no bridge in the path.
 *
 * The screen stays open for the duration on purpose. The provisioning socket lives about
 * ninety seconds and dies with the scope that holds it, so a code shown and then navigated
 * away from is a code that has already stopped working -- which would read as "the scan
 * failed" rather than "you left".
 */
class SignalLinkActivity : QkThemedActivity() {

    @Inject lateinit var signalRepo: SignalRepository

    private lateinit var binding: SignalLinkActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        binding = SignalLinkActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.signal_link_title)
        showBackButton(true)

        CoroutineScope(Dispatchers.IO).launch {
            val result = signalRepo.linkDevice(
                deviceName = android.os.Build.MODEL ?: "kotozute",
                onUrl = { url -> CoroutineScope(Dispatchers.Main).launch { show(url) } }
            )
            withContext(Dispatchers.Main) {
                binding.status.text = when {
                    result == null -> getString(R.string.signal_link_expired)
                    result.startsWith("linked") -> getString(R.string.signal_link_success)
                    else -> getString(R.string.signal_link_failed, result)
                }
            }
        }
    }

    private fun show(url: String) {
        // Never logged, and never written down. This string is a live offer to join the
        // account -- whoever redeems it first becomes a device on it -- so it exists on this
        // screen, and, while this screen is open, on the page this phone serves to its
        // owner's own computer. Nowhere else, and not for a second longer. See
        // [SignalLinkOffer] for why the computer has to be offered it at all.
        binding.qr.setImageBitmap(qrOf(url))
        binding.status.setText(R.string.signal_link_waiting)
        SignalLinkOffer.offer(url)
        showComputerRoute()
    }

    /**
     * Where to read this code if the phone in your hand is the one already running Signal.
     *
     * Shown only when Desktop Sync is on and has an address to give, because the sentence is
     * useless otherwise -- and a phone with no computer on its tailnet still has the QR and
     * a second phone, which is the ordinary way in.
     */
    private fun showComputerRoute() {
        if (!prefs.desktopSyncEnabled.get()) return
        val token = prefs.desktopSyncToken.get()
        if (token.isBlank()) return
        val address = DesktopSyncService.reachableAddresses(this)
            .filter {
                !prefs.desktopSyncTailscaleOnly.get() ||
                    it.first == DesktopSyncService.LABEL_TAILSCALE
            }
            .map { (_, host) ->
                "http://$host:${DesktopSyncService.PORT}/signal-link?token=$token"
            }
            .firstOrNull() ?: return
        binding.onComputer.text = getString(R.string.signal_link_on_computer, address)
        binding.onComputer.visibility = android.view.View.VISIBLE
    }

    /**
     * ⚠ The code stops being offered when this screen goes, not when it expires. The socket
     * behind it dies with this activity's scope anyway, so a code left on the page after that
     * would be a live-looking offer that cannot be redeemed -- and a secret kept past its use.
     */
    override fun onDestroy() {
        SignalLinkOffer.offer(null)
        super.onDestroy()
    }

    /**
     * A black-and-white bitmap, deliberately without a margin quiet zone of its own beyond the
     * minimum: the screen this renders on is small, and every module of the code has to be
     * large enough for another phone's camera to resolve off a low-contrast panel.
     */
    private fun qrOf(text: String): Bitmap {
        val size = 512
        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                // A linking URL is long, and the correction level trades capacity against
                // readability. L keeps the modules as large as possible, which matters more
                // than redundancy for a code being read once, from a screen, immediately.
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.MARGIN to 1
            )
        )
        return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
            for (x in 0 until size) {
                for (y in 0 until size) {
                    setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
        }
    }

    companion object {
        fun intent(context: Context) = Intent(context, SignalLinkActivity::class.java)
    }
}
