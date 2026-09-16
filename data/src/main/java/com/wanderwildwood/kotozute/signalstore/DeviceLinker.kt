package com.wanderwildwood.kotozute.signalstore

import org.signal.core.models.ServiceId
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.util.KeyHelper
import org.signal.network.api.RegistrationApiV2
import org.signal.network.config.SignalServiceConfiguration
import org.signal.network.rest.SignalRestClient
import kotlinx.coroutines.suspendCancellableCoroutine
import org.whispersystems.signalservice.api.provisioning.ProvisioningSocket
import org.whispersystems.signalservice.api.util.CredentialsProvider
import org.whispersystems.signalservice.internal.crypto.SecondaryProvisioningCipher
import org.whispersystems.signalservice.internal.push.ProvisionMessage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import timber.log.Timber
import java.security.SecureRandom
import java.util.Base64

/**
 * Links this phone to an existing Signal account as a secondary device.
 *
 * The same exchange Molly and Signal Desktop perform when you scan their QR, and the reason
 * this branch exists: once it completes, the bridge on the always-on computer is no longer in
 * the path.
 *
 * The shape, which is signal-cli's:
 *
 *  1. Generate a throwaway key pair and **a password of our own** before anything is sent.
 *  2. Open a provisioning socket and show the URL it returns as a QR.
 *  3. The primary device encrypts the account's real identity to that key and sends it back.
 *  4. Build fresh registration ids and one signed + one last-resort Kyber pre key per identity.
 *  5. Register, receive a device id, and only then write anything down.
 *
 * Step 1's password is the part that surprises: it is chosen here, never transmitted in the
 * provisioning message and never returned by the server, and it is half this device's
 * credential for as long as it exists.
 */
class DeviceLinker internal constructor(
    private val configuration: SignalServiceConfiguration,
    private val userAgent: String,
    private val accounts: SignalAccountStore,
    private val signedPreKeys: (Int) -> SignalSignedPreKeyStore,
    private val kyberPreKeys: (Int) -> SignalKyberPreKeyStore,
    /**
     * Takes the account's entropy pool, which the provisioning message already carried.
     *
     * ⚠ Everything was thrown away except the profile key, and the storage key was then asked
     * for separately -- by a `SyncMessage.Request` of type KEYS that only goes out when
     * somebody taps "fetch contacts from Signal", and that only answers while the primary is
     * awake. So a freshly linked phone had no contacts and no groups, and getting them needed
     * a second, manual, round trip for a key the primary had *already handed over* in the QR
     * exchange. Signal takes the pool out of the same message at link time and persists it
     * before the device talks to the primary at all; its KEYS request is a recovery path.
     */
    private val onAccountKeys: (String) -> Unit = {},
    /** The account's read-receipt setting, which rides the same message. */
    private val onReadReceipts: (Boolean) -> Unit = {}
) {

    /** What the caller shows as a QR while it waits. */
    fun interface UrlListener {
        fun onUrl(url: String)
    }

    sealed interface Result {
        data class Linked(val deviceId: Int, val e164: String?) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Runs the whole exchange.
     *
     * Suspending because the socket is, and because the wait in the middle is a person: the
     * URL arrives immediately, the provisioning message only once somebody redeems it. The
     * socket's own lifespan is **90 seconds**, so the URL is worth showing the moment it
     * arrives rather than after any further setup.
     *
     * `ProvisioningSocket.start` looks synchronous and is not. It launches the block on a
     * scope of its own and hands back a `Closeable` that **cancels that scope** -- so the
     * obvious `.use { }` around it closes the socket before the block has run, and the
     * exchange fails having never opened. It cost a full round trip to find, because the
     * symptom is a link that fails instantly with no URL and no error. The Closeable is
     * therefore held and closed once, at the end, and on cancellation.
     */
    suspend fun link(deviceName: String, onUrl: UrlListener): Result {
        val provisioningKeys = IdentityKeyPair.generate()
        val password = generatePassword()

        val provision = try {
            awaitProvisionMessage(provisioningKeys, onUrl)
        } catch (t: Throwable) {
            return Result.Failed(t.message ?: t::class.java.simpleName)
        } ?: return Result.Failed("the provisioning message could not be decrypted")

        return register(provision, password, deviceName)
    }

    /**
     * Publishes a link URL and waits for the primary to answer it.
     *
     * ⚠ One socket with a ninety-second life, and the ninety seconds are not ours to extend:
     * `ProvisioningSocket.LIFESPAN` is ninety seconds and the library cancels its own scope
     * with a `SocketTimeoutException` when it elapses. That clock starts the moment the QR is
     * shown -- before the person has picked up the other phone, found Linked Devices and
     * pointed the camera -- and running out surfaced the raw socket exception as the failure
     * reason. The natural response, try again, re-armed exactly the same ninety seconds.
     *
     * Signal does not extend the life either. It opens a **new** socket every LIFESPAN/2 and
     * republishes its URL as the QR, five times over, so the code on screen is never more than
     * forty-five seconds old and linking has about four and a half minutes to happen in.
     *
     * The part that is easy to miss is why it keeps *two*: closing the old socket the instant
     * a new one opens would strand somebody who scanned at forty-four seconds and is
     * mid-exchange. So each new socket displaces the one before last, never the one before.
     * `RegisterLinkDeviceQrViewModel.startNewSocket` is those four lines.
     *
     * A failure from any socket but the last is not a failure of the linking: it is an old
     * code expiring, which is the ordinary case and now says nothing at all.
     */
    private suspend fun awaitProvisionMessage(
        provisioningKeys: IdentityKeyPair,
        onUrl: UrlListener
    ): ProvisionMessage? = suspendCancellableCoroutine { continuation ->
        // The socket resumes this from its own coroutine and the exception handler resumes it
        // from another; whichever arrives first wins and the rest are dropped. Without that a
        // failure after a success -- the socket closing normally, say -- would resume twice
        // and throw from inside the library's scope.
        val done = AtomicBoolean(false)

        // Newest last. Guarded by itself, because the rotation timer and a socket's own
        // callback both reach it.
        val handles = java.util.ArrayList<java.io.Closeable>()
        val rotations = AtomicInteger(0)
        // How many sockets have been opened, and how many of those have since failed. The
        // difference is how many codes are still scannable -- see [noCodeIsStillLive].
        val opened = AtomicInteger(0)
        val failed = AtomicInteger(0)

        val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "signal-link-rotate").apply { isDaemon = true }
        }

        fun closeAll() {
            synchronized(handles) {
                handles.forEach { runCatching { it.close() } }
                handles.clear()
            }
            timer.shutdownNow()
        }

        fun finish(block: () -> Unit) {
            if (done.compareAndSet(false, true)) {
                block()
                closeAll()
            }
        }

        fun openSocket() {
            if (done.get()) return
            val closeable = runCatching {
                ProvisioningSocket.start<ProvisionMessage>(
                    ProvisioningSocket.Mode.Link(false),
                    provisioningKeys,
                    configuration,
                    { id, t ->
                        // Only when nothing is left to scan. An earlier socket timing out
                        // is a code expiring on schedule, which is what is supposed to happen
                        // to it -- see [noCodeIsStillLive].
                        if (noCodeIsStillLive(
                                rotations = rotations.get(),
                                opened = opened.get(),
                                failed = failed.incrementAndGet()
                            )
                        ) {
                            Timber.w(t, "signal link: the last provisioning socket failed")
                            finish {
                                continuation.resumeWithException(
                                    IllegalStateException(
                                        "nobody scanned the code in time -- ask for a new one",
                                        t
                                    )
                                )
                            }
                        } else {
                            Timber.i("signal link: provisioning socket %d expired; a newer code is up", id)
                        }
                    }
                ) { socket ->
                    // Republished each time, which is the point of opening a new one.
                    onUrl.onUrl(socket.getProvisioningUrl())
                    val decrypted = socket.getProvisioningMessageDecryptResult()
                    finish {
                        continuation.resume(
                            (decrypted as? SecondaryProvisioningCipher.ProvisioningDecryptResult.Success)?.message
                        )
                    }
                }
            }.onFailure { Timber.w(it, "signal link: could not open a provisioning socket") }
                .getOrNull() ?: return

            opened.incrementAndGet()
            val displaced = synchronized(handles) { admit(handles, closeable) }
            runCatching { displaced?.close() }

            // If the exchange finished while start() was returning, nothing above will close
            // this one.
            if (done.get()) closeAll()
        }

        openSocket()
        timer.scheduleAtFixedRate(
            {
                if (done.get()) return@scheduleAtFixedRate
                if (rotations.incrementAndGet() > MAX_LINK_ROTATIONS) return@scheduleAtFixedRate
                openSocket()
            },
            LINK_ROTATE_INTERVAL_MS,
            LINK_ROTATE_INTERVAL_MS,
            java.util.concurrent.TimeUnit.MILLISECONDS
        )

        // Closed on every exit, including the caller giving up. A socket is a live offer to
        // join the account; leaving one open because nobody cancelled it is the wrong default.
        continuation.invokeOnCancellation { closeAll() }
    }

    private suspend fun register(
        provision: ProvisionMessage,
        password: String,
        deviceName: String
    ): Result {
        val aci = ServiceId.ACI.parseOrThrow(provision.aci, provision.aciBinary)
        // ⚠ The binary field first, as the ACI above already did. A modern primary sends only
        // `pniBinary` and leaves the deprecated string empty, and reading the string alone
        // left the account row with no PNI at all -- which is not a cosmetic gap: group
        // authorisation parses it, and the store throws "the account has no PNI" when asked.
        // Upstream reads `message.pniBinary ?: message.pni`, exactly this way round.
        val pni = ServiceId.PNI.parseOrNull(provision.pni, provision.pniBinary)
        val aciIdentity = provision.aciIdentityKeyPair()
        val pniIdentity = provision.pniIdentityKeyPair()

        // Ours, not the primary's. libsignal uses these to recognise a session as belonging to
        // this installation, so they must be generated here and kept.
        val aciRegistrationId = KeyHelper.generateRegistrationId(false)
        val pniRegistrationId = KeyHelper.generateRegistrationId(false)

        val aciKeys = preKeyCollection(ProtocolDatabase.ACCOUNT_ID_TYPE_ACI, aciIdentity)
        val pniKeys = preKeyCollection(ProtocolDatabase.ACCOUNT_ID_TYPE_PNI, pniIdentity)

        // Authenticates as the account we are joining, with the password we invented. There is
        // no device id yet -- the server is about to assign one.
        val credentials = object : CredentialsProvider {
            override fun getAci() = aci
            override fun getPni(): ServiceId.PNI? = null
            override fun getE164(): String? = provision.number
            override fun getDeviceId() = 0
            override fun getPassword() = password
        }

        val api = RegistrationApiV2(
            SignalRestClient(configuration, userAgent, credentials),
            false
        )

        val attributes = RegistrationApiV2.DeviceAttributes(
            // This device fetches its own messages rather than being pushed to by Google.
            true,
            aciRegistrationId,
            pniRegistrationId,
            encryptDeviceName(deviceName, aciIdentity),
            // Not optional, and not a wish list. All six declared false is what the server
            // answers with `MissingCapability`, which is how this was found: the link is
            // refused outright rather than degraded. These are the values signal-cli sends
            // for a secondary device, and they are promises this app now owes:
            //
            //   storage                   -- the encrypted storage service (contacts, groups)
            //   versionedExpirationTimer  -- versioned disappearing-message timers
            //   attachmentBackfill        -- answering backfill requests for attachments
            //   spqr                      -- the sparse post-quantum ratchet
            //   usernameChangeSyncMessage -- username-change sync messages
            //   optionalPhoneNumber       -- working without a visible phone number
            //
            // A linked device is expected to speak all of them, so there is no honest smaller
            // claim to make; what is left is to actually handle each, and where the app does
            // not yet, that is a gap to close rather than a flag to unset.
            //
            // ⚠ With one exception, checked against upstream: **attachmentBackfill is not a
            // gap.** Signal's own linked devices answer a backfill request by ignoring it --
            // `SyncMessageProcessor.handleSynchronizeAttachmentBackfillRequest` returns
            // immediately when `isLinkedDevice`. Only a primary answers. Doing nothing here is
            // the correct behaviour, not an unfinished one.
            SignalCapabilities.forLinking()
        )

        return when (val result = api.registerAsSecondaryDevice(
            aci,
            password,
            // The code the primary device put in the provisioning message. Its absence is not
            // a recoverable state -- without it the server has no reason to believe this
            // device was invited -- so it fails here rather than being sent as empty.
            provision.provisioningCode ?: return Result.Failed("no provisioning code in the message"),
            attributes,
            aciKeys,
            pniKeys,
            null
        )) {
            is org.signal.libsignal.net.RequestResult.Success -> {
                val deviceId = result.result.deviceId
                // Written only now. Everything above is discardable; from here the device
                // exists on the account and losing the password means it cannot be reached.
                // Anything the previous link built is describing a device that no longer
                // exists: this one has a new device id, new identity keys and new
                // registration ids. A session carried over from it encrypts to the old
                // device, and the recipient has nothing that matches -- the message arrives
                // undecryptable with nothing in the thread to explain it.
                //
                // Before the new identity is written, so a failure here leaves the device
                // unlinked rather than half-linked.
                accounts.forgetSessionsFromPreviousAccount()

                // The sender certificate is one of those things, and it outlives a relink on
                // its own: the cache is process-wide and linking again does not restart the
                // process. See [SealedSender.forgetCertificate].
                SealedSender.forgetCertificate()

                accounts.saveIdentity(ProtocolDatabase.ACCOUNT_ID_TYPE_ACI, aciIdentity, aciRegistrationId)
                accounts.saveIdentity(ProtocolDatabase.ACCOUNT_ID_TYPE_PNI, pniIdentity, pniRegistrationId)
                accounts.saveCredentials(
                    provision.number,
                    aci.toString(),
                    pni?.toString(),
                    deviceId,
                    password
                )
                provision.profileKey?.let { accounts.saveProfileKey(it.toByteArray()) }

                // Field 19 of the provisioning message, and new here: the service layer this
                // app used before was built from a Signal source that predated it, so the
                // field arrived and was dropped. An account **with** a phone number never
                // needs it; an account without one cannot authorize a single group without it
                // (`GroupsV2Api.getGroupsV2AuthorizationString` -> `receiveAuthCredentialWithoutPni`).
                // Signal stores it at the same point, `AppRegistrationStorageController:812`.
                provision.authCredentialSalt
                    ?.takeIf { it.size > 0 }
                    ?.let { accounts.saveAuthCredentialSalt(it.toByteArray()) }

                // The storage key, from the pool this message already carried. Done here so
                // the first storage read can happen on this device's own initiative rather
                // than waiting on a KEYS round trip somebody has to ask for.
                provision.accountEntropyPool?.takeIf { it.isNotBlank() }?.let { pool ->
                    runCatching { onAccountKeys(pool) }
                        .onFailure { Timber.w(it, "signal link: the account keys would not keep") }
                }

                // And the account's read-receipt setting, rather than this device's default
                // until a Configuration sync happens to land.
                provision.readReceipts?.let { on ->
                    runCatching { onReadReceipts(on) }
                        .onFailure { Timber.w(it, "signal link: the read-receipt setting would not keep") }
                }
                Timber.i("signal link: linked as device %d", deviceId)
                Result.Linked(deviceId, provision.number)
            }
            else -> Result.Failed("registration refused: $result")
        }
    }

    /**
     * One signed pre key and one last-resort Kyber key per identity — exactly what the
     * registration call carries. The hundred one-time keys come afterwards, in their own
     * upload; sending them here would be the wrong request.
     */
    private fun preKeyCollection(
        accountIdType: Int,
        identity: IdentityKeyPair
    ): RegistrationApiV2.PreKeyCollection {
        val signedId = accounts.nextSignedPreKeyId(accountIdType)
        val kyberId = accounts.nextKyberPreKeyId(accountIdType)
        val signed = KeyUtilsForCheck.signedPreKey(signedId, identity.privateKey)
        val kyber = KeyUtilsForCheck.kyberPreKey(kyberId, identity.privateKey)

        // Stored, not merely counted. Recording the ids as active without keeping the keys
        // leaves the server advertising a signed pre key whose private half exists nowhere --
        // and the symptom is a long way from here: the first person to message this device
        // gets through to `no signed pre key 1` at decryption time, which reads as a corrupt
        // message rather than a missing key. Found exactly that way.
        signedPreKeys(accountIdType).storeSignedPreKey(signedId, signed)
        kyberPreKeys(accountIdType).storeLastResortKyberPreKey(kyberId, kyber)

        accounts.recordActiveSignedPreKey(accountIdType, signedId)
        accounts.recordActiveLastResortKyberPreKey(accountIdType, kyberId)
        return RegistrationApiV2.PreKeyCollection(identity.publicKey, signed, kyber)
    }

    /** Signal's own device list shows this, so it is encrypted to the account identity. */
    private fun encryptDeviceName(name: String, identity: IdentityKeyPair): String =
        Base64.getEncoder().withoutPadding().encodeToString(
            org.signal.core.util.crypto.DeviceNameCipher.encryptDeviceName(
                name.toByteArray(Charsets.UTF_8), identity
            )
        )

    companion object {

        /**
         * Whether every code this attempt will ever show has now expired.
         *
         * ⚠ The first version asked whether the *rotation counter* had reached its maximum,
         * which is a different question and got the answer wrong by a whole rotation. The
         * timer keeps ticking after the last socket opens, so the counter reaches five while
         * two sockets are still alive -- and the next one to expire is an **old** socket, not
         * the newest. The attempt was abandoned at t=225s with two scannable codes on the
         * wire, one of them put there that same second: from the other side of the screen, a
         * fresh code appearing and instantly failing.
         *
         * The right question is upstream's -- report failure only when the *current* socket
         * fails -- expressed as arithmetic rather than socket identity, because the library
         * hands the id to the callback and not to the opener. No more rotations are coming,
         * **and** every socket opened has since failed, so there is nothing left to scan.
         *
         * @param failed counted *including* the failure being reported.
         */
        internal fun noCodeIsStillLive(rotations: Int, opened: Int, failed: Int): Boolean =
            rotations > MAX_LINK_ROTATIONS && failed >= opened

        /**
         * Adds a new socket handle and returns the one it displaces, if any.
         *
         * ⚠ **Two**, not one, and that is the whole rule. Closing the previous socket the
         * instant a new one opens would strand anybody who scanned the previous code and is
         * part-way through the exchange. Keeping two means a code stays usable for one full
         * rotation after it stops being displayed.
         *
         * Signal's `startNewSocket`: append, and while there are more than two, drop the
         * oldest.
         */
        internal fun <T> admit(handles: MutableList<T>, incoming: T, keep: Int = 2): T? {
            handles += incoming
            return if (handles.size > keep) handles.removeAt(0) else null
        }

        /**
         * How often a fresh link code replaces the one on screen.
         *
         * Half of `ProvisioningSocket.LIFESPAN`, which is ninety seconds -- Signal's
         * `delay(ProvisioningSocket.LIFESPAN / 2)`. Half, so the code showing is never older
         * than the rotation interval and never close to its own expiry.
         */
        val LINK_ROTATE_INTERVAL_MS = java.util.concurrent.TimeUnit.SECONDS.toMillis(45)

        /**
         * How many times it is replaced before giving up. Signal's `count < 5`.
         *
         * Five rotations at forty-five seconds is about four and a half minutes of linking
         * time, against the ninety seconds a single socket allows.
         */
        const val MAX_LINK_ROTATIONS = 5

        /**
         * Eighteen random bytes, base64, following signal-cli's `KeyUtils.createPassword`.
         *
         * Invented here and never transmitted during provisioning. It becomes half of this
         * device's credential permanently, so it is generated before anything else and written
         * down only once the server has accepted it.
         */
        fun generatePassword(): String =
            Base64.getEncoder().withoutPadding()
                .encodeToString(ByteArray(18).also { SecureRandom().nextBytes(it) })
    }
}

/** The provisioning message carries both identities as separate public and private halves. */
private fun ProvisionMessage.aciIdentityKeyPair() =
    IdentityKeyPair(
        org.signal.libsignal.protocol.IdentityKey(aciIdentityKeyPublic!!.toByteArray()),
        org.signal.libsignal.protocol.ecc.ECPrivateKey(aciIdentityKeyPrivate!!.toByteArray())
    )

private fun ProvisionMessage.pniIdentityKeyPair() =
    IdentityKeyPair(
        org.signal.libsignal.protocol.IdentityKey(pniIdentityKeyPublic!!.toByteArray()),
        org.signal.libsignal.protocol.ecc.ECPrivateKey(pniIdentityKeyPrivate!!.toByteArray())
    )
