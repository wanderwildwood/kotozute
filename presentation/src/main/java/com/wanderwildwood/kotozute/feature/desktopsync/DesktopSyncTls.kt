package com.wanderwildwood.kotozute.feature.desktopsync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import timber.log.Timber
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Calendar
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.security.auth.x500.X500Principal

/**
 * Serving Desktop Sync over TLS with a certificate this phone makes for itself.
 *
 * ## Why this exists
 *
 * Desktop Sync is served over plain HTTP on a LAN address, and a browser withholds a growing
 * list of things from a page that is not a **secure context** -- among them the microphone,
 * which is what a voice note needs, and the clipboard, which this app already works around.
 * No amount of client code changes that; the transport decides it.
 *
 * ## What it costs, said plainly
 *
 * ⚠ **The certificate is self-signed, so every browser will interrupt with a full-page
 * warning** the first time, and the person has to choose to continue. That is not a bug to be
 * tidied away: it is the browser correctly reporting that nothing vouches for this phone but
 * the phone. There is no way around it short of a real certificate for a real name, which a
 * device on a home network does not have.
 *
 * ⚠ **A paired browser will need the link again**, because the scheme changes.
 *
 * ## Why AndroidKeyStore
 *
 * It generates a self-signed certificate for a key pair as a side effect of creating one, so
 * no certificate-building library is needed and the private key never exists outside the
 * keystore. The alternative was adding BouncyCastle, which this repo's dependency verification
 * would then need checksums for -- a supply-chain decision to take deliberately, not to smuggle
 * in behind a feature.
 *
 * ⚠ **The key is kept, not regenerated.** A new certificate on every start means the warning
 * comes back every time and any exception the person granted is void.
 */
internal object DesktopSyncTls {

    /**
     * ⚠ Versioned, so a key made by an older build is replaced rather than reused.
     *
     * Two earlier shapes could not serve TLS at all — an RSA key (raw-RSA upcall refused) and
     * an EC key that allowed only SHA-256 (Conscrypt pre-hashes and asks for a **raw**
     * signature). Both produced a socket that accepted connections and then died mid-handshake,
     * which is indistinguishable from a working server until a browser tries it. Bumping the
     * alias is how a phone that already has one gets out of that.
     */
    private const val ALIAS = "desktop-sync-tls-v3"
    private const val KEYSTORE = "AndroidKeyStore"

    /**
     * A factory for TLS sockets, or null if this device would not make a certificate.
     *
     * Null is a real answer and the caller must handle it: refusing to serve at all would be
     * worse than serving as before, and pretending it worked would leave somebody looking for
     * a page that is not there.
     */
    fun serverSocketFactory(context: Context): SSLServerSocketFactory? = runCatching {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        // ⚠ Regenerated when the key is not the kind this code now makes. An earlier build
        // made an RSA key here, and an RSA key from the keystore cannot serve TLS at all --
        // see [generate]. Without this check that phone would keep failing every handshake
        // with a key it will never be able to use.
        val usable = runCatching {
            (keyStore.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry)?.privateKey?.algorithm == "EC"
        }.getOrDefault(false)
        if (!usable) {
            runCatching { keyStore.deleteEntry(ALIAS) }
            generate()
        }

        // ⚠ Loaded again rather than reusing the handle above: `generate` writes through the
        // keystore, and an instance loaded before the entry existed does not see it.
        val loaded = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val entry = loaded.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry

        SSLContext.getInstance("TLS")
            .apply { init(arrayOf(keyManagerFor(entry)), null, null) }
            .serverSocketFactory
    }.onFailure {
        Timber.w(it, "desktop sync: could not prepare a certificate; serving without TLS")
    }.getOrNull()

    /**
     * Hands the keystore's key and certificate straight to TLS.
     *
     * ⛔ **The default `KeyManagerFactory` cannot do this, and fails silently.** Initialised
     * from an AndroidKeyStore it produces a key manager that offers **no certificate**, so the
     * socket is a real TLS socket that has nothing to present and every handshake dies with
     * `Cipher is (NONE)` -- a server that looks like it is running and refuses every browser.
     * Measured, not guessed: that is exactly what the first version of this did.
     *
     * The cause is that a keystore private key is never exported, and the default factory
     * wants the key material. A key manager that returns the `PrivateKey` handle instead lets
     * the keystore do the signing, which is the whole point of it.
     */
    private fun keyManagerFor(entry: KeyStore.PrivateKeyEntry): javax.net.ssl.X509KeyManager {
        val chain = entry.certificateChain
            .map { it as java.security.cert.X509Certificate }
            .toTypedArray()
        return object : javax.net.ssl.X509ExtendedKeyManager() {
            override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?) = ALIAS
            override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = arrayOf(ALIAS)
            override fun getCertificateChain(alias: String?) = chain
            override fun getPrivateKey(alias: String?) = entry.privateKey
            // This side never acts as a client.
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?): String? = null
            override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? = null
        }
    }

    /**
     * Makes the key pair, and with it the self-signed certificate the keystore derives.
     *
     * ⚠ RSA rather than EC, and 2048 rather than more: this is negotiated once per connection
     * on a phone, and every browser accepts it. The subject is a name, not an address --
     * nothing can vouch for an address that changes with the network, and the browser is going
     * to warn either way.
     */
    private fun generate() {
        val notBefore = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, 10) }

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    // ⛔ **EC, not RSA, and this is not a preference.** A keystore RSA key
                    // cannot serve TLS: Conscrypt reaches for a raw `RSA/ECB/NoPadding`
                    // operation during the handshake, which a SIGN-only keystore key refuses
                    // with `KeyStoreException: Incompatible padding mode`. The socket accepts
                    // the connection and then dies mid-handshake, so it looks like a working
                    // server that every browser rejects. Measured on device; the first version
                    // of this was RSA and did exactly that.
                    //
                    // ECDSA is signed directly, so the keystore is asked only for the one
                    // operation it was created to do. P-256 with SHA-256 is what every browser
                    // negotiates anyway, and it needs no raw-RSA permission on the key.
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    // ⚠ **NONE as well as the named digests.** Conscrypt hashes the handshake
                    // itself and hands the keystore the digest to sign raw; a key that allows
                    // only SHA-256 refuses that with `KeyStoreException: Incompatible digest`
                    // and the handshake dies without a word to the client. Measured on device.
                    .setDigests(
                        KeyProperties.DIGEST_NONE,
                        KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384,
                        KeyProperties.DIGEST_SHA512
                    )
                    .setCertificateSubject(X500Principal("CN=Desktop Sync"))
                    .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                    .setCertificateNotBefore(notBefore.time)
                    .setCertificateNotAfter(notAfter.time)
                    // ⛔ Deliberately not requiring the screen to be unlocked. The server runs
                    // while the phone sits on a desk with the panel off, which is the whole
                    // point of Desktop Sync; a key that needed an unlock would make it fail
                    // exactly when it is being used.
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            generateKeyPair()
        }
        Timber.i("desktop sync: made a certificate for this phone")
    }
}
