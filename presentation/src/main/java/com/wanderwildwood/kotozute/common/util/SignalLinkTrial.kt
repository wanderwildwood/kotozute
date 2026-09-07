package com.wanderwildwood.kotozute.common.util

import android.content.Context
import com.wanderwildwood.kotozute.signalstore.DeviceLinker
import com.wanderwildwood.kotozute.signalstore.SignalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * EXPERIMENT (signal-on-the-phone branch). Links this device to the real account, once, by
 * hand.
 *
 * Scaffolding for the QR screen that does not exist yet. It exists so the exchange can be
 * driven end to end -- against Signal's actual servers and a real primary device -- before
 * any UI is written around it, because everything the UI would show depends on what this
 * turns out to do.
 *
 * **Gated on a marker file, deliberately, and not on "no account yet".** The obvious gate is
 * wrong twice over: it would open a provisioning socket to Signal on every launch of every
 * unlinked debug build, and it would print a live linking URL to the log each time. That URL
 * is a standing offer to join the account -- whoever redeems it first becomes a device on it.
 * So it is printed only when somebody has asked for it, in the same minute they are going to
 * use it:
 *
 *     adb shell run-as com.wanderwildwood.kotozute.debug touch files/link-now
 *
 * The marker is deleted before the socket opens, so a crash or a second launch cannot reissue
 * one. One launch, one offer.
 */
object SignalLinkTrial {

    private const val MARKER = "link-now"

    fun runIfRequested(
        context: Context,
        deviceName: String,
        file: (List<com.wanderwildwood.kotozute.signal.BridgeMessage>) -> Int,
        threads: () -> String
    ) {
        val marker = File(context.filesDir, MARKER)
        if (!marker.exists()) return
        // Before anything is issued, not after. A URL that outlives its request is the thing
        // worth preventing here.
        marker.delete()

        val store = SignalStore(context)
        if (store.isLinked()) {
            // Already a device on the account, so the marker means the other half of linking:
            // publish a batch of one-time pre keys. Safe to repeat -- a fresh batch replaces
            // what the server holds rather than adding to it.
            Timber.i("signal link: already linked; publishing keys, then receiving")
            CoroutineScope(Dispatchers.IO).launch {
                // Keys first. Receiving before the server and this device agree on the
                // repeated-use keys just produces undecryptable envelopes, and they are acked
                // on the way past -- so the order matters more than it looks.
                runCatching { store.uploadPreKeys(SignalNetworkConfig.USER_AGENT) }
                    .onSuccess { Timber.i("signal keys: %s", it) }
                    .onFailure { Timber.e(it, "signal keys: threw") }
                runCatching {
                    store.receive(SignalNetworkConfig.USER_AGENT, SignalNetworkConfig.certificateValidator(), file)
                }
                    .onSuccess { Timber.i("signal receive: %s", it) }
                    .onFailure { Timber.e(it, "signal receive: threw") }
                // What actually landed in Realm, asked of the rail rather than inferred from
                // the return value -- storing and being visible are different claims.
                runCatching { threads() }
                    .onSuccess { Timber.i("signal receive: threads in realm = %s", it) }
                    .onFailure { Timber.w(it, "signal receive: could not read threads") }
            }
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val linker = store.linker(SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT)
                val result = linker.link(deviceName) { url ->
                    // In full, and only here. This is the string a primary device redeems.
                    Timber.i("signal link: URL %s", url)
                }
                when (result) {
                    is DeviceLinker.Result.Linked ->
                        Timber.i("signal link: LINKED as device %d on %s", result.deviceId, result.e164)
                    is DeviceLinker.Result.Failed ->
                        Timber.w("signal link: FAILED -- %s", result.reason)
                }
            }.onFailure { Timber.e(it, "signal link: threw") }
        }
    }
}
