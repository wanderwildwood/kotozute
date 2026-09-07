package com.wanderwildwood.kotozute.common.util

import org.signal.libsignal.protocol.IdentityKeyPair
import org.whispersystems.signalservice.internal.crypto.SecondaryProvisioningCipher
import org.whispersystems.signalservice.internal.push.ProvisioningSocket
import timber.log.Timber

/**
 * EXPERIMENT (signal-on-the-phone branch). Does libsignal actually work on this phone?
 *
 * Being linked and dexed is not the same as being loadable. The library is 112 MB of Rust
 * behind a JNI boundary, built for arm64-v8a against an NDK of Signal's choosing, and the
 * Kompakt is Android 12 on a four-core A53. Whether `System.loadLibrary` succeeds there, and
 * whether the curve arithmetic then runs, is the question every other question about this
 * route sits on top of. It is cheap to answer and nobody had.
 *
 * So this generates a real identity key, signs with it and verifies the signature -- all of
 * which cross into the native library and would fail loudly if it were not there.
 *
 * Debug builds only, once at startup, and it catches everything: a smoke test that could take
 * the app down would be a worse bug than the one it is looking for.
 */
object LibsignalSmokeTest {

    /** Signal rejects clients that do not identify themselves. */
    private const val USER_AGENT = "kotozute-experiment"

    fun run() {
        val started = System.currentTimeMillis()
        runCatching {
            val identity = IdentityKeyPair.generate()
            val message = "kotozute libsignal smoke test".toByteArray()
            val signature = identity.privateKey.calculateSignature(message)
            val verified = identity.publicKey.publicKey.verifySignature(message, signature)
            val elapsed = System.currentTimeMillis() - started

            // Both halves matter. A signature that verifies proves the curve arithmetic ran;
            // the timing says whether it is affordable on this processor.
            Timber.i(
                "libsignal: identity generated, signature %s, in %d ms",
                if (verified) "verified" else "DID NOT VERIFY",
                elapsed
            )
            // Now cross the version boundary. SecondaryProvisioningCipher is the first step
            // of linking a device -- the secondary generates a key, shows its public half in
            // the QR, and decrypts what the primary sends back. It comes from a service layer
            // compiled against libsignal 0.76 and is here being handed a key from 0.102, which
            // is the mismatch Gradle resolved silently. If twenty-six minor versions of drift
            // matter, this is where a NoSuchMethodError appears.
            val cipher = SecondaryProvisioningCipher(identity)
            val devicePublicKey = cipher.secondaryDevicePublicKey
            Timber.i(
                "libsignal-service: provisioning cipher built, device key %d bytes",
                devicePublicKey.serialize().size
            )
            // The real thing: open a provisioning socket against Signal's own servers and ask
            // for a provisioning address. Nothing is linked and no account is touched -- this
            // is the anonymous half of the handshake, the half that produces the QR a primary
            // device scans. If it returns, then the ported configuration, the pinned trust
            // store, the websocket and TLS to Signal all work from this phone.
            val socket = ProvisioningSocket(SignalNetworkConfig.production(), USER_AGENT)
            val address = socket.provisioningUuid
            Timber.i("signal-net: provisioning address obtained, uuid begins %s", address.uuid.take(8))
        }.onFailure {
            // An UnsatisfiedLinkError here is the whole answer: the library did not load, and
            // no amount of client code would change that.
            Timber.e(it, "libsignal: did not run")
        }
    }
}
