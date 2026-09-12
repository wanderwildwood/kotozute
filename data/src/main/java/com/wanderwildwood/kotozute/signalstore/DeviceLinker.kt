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
import java.util.concurrent.atomic.AtomicReference
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
    private val kyberPreKeys: (Int) -> SignalKyberPreKeyStore
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

    private suspend fun awaitProvisionMessage(
        provisioningKeys: IdentityKeyPair,
        onUrl: UrlListener
    ): ProvisionMessage? = suspendCancellableCoroutine { continuation ->
        // The socket resumes this from its own coroutine and the exception handler resumes it
        // from another; whichever arrives first wins and the rest are dropped. Without that a
        // failure after a success -- the socket closing normally, say -- would resume twice
        // and throw from inside the library's scope.
        // Held so that whichever path finishes first can also close the socket. It is
        // assigned just below, but the exception handler can in principle fire before start()
        // has returned, so it is a reference rather than a val and closing tolerates null --
        // the `closed` flag then makes the assignment close it instead.
        val socket = AtomicReference<java.io.Closeable?>(null)
        val done = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        fun closeSocket() {
            if (closed.compareAndSet(false, true)) socket.get()?.close()
        }
        fun finish(block: () -> Unit) {
            if (done.compareAndSet(false, true)) {
                block()
                closeSocket()
            }
        }

        val closeable = ProvisioningSocket.start<ProvisionMessage>(
            ProvisioningSocket.Mode.Link(false),
            provisioningKeys,
            configuration,
            { id, t ->
                Timber.w(t, "signal link: provisioning socket %d failed", id)
                finish { continuation.resumeWithException(t) }
            }
        ) { socket ->
            onUrl.onUrl(socket.getProvisioningUrl())
            val decrypted = socket.getProvisioningMessageDecryptResult()
            finish {
                continuation.resume(
                    (decrypted as? SecondaryProvisioningCipher.ProvisioningDecryptResult.Success)?.message
                )
            }
        }

        socket.set(closeable)
        // Closed on every exit, including the caller giving up. The socket is a live offer to
        // join the account; leaving one open because nobody cancelled it is the wrong default.
        // If the exchange already finished while start() was returning, close it now.
        if (done.get()) closeSocket()
        continuation.invokeOnCancellation { closeSocket() }
    }

    private suspend fun register(
        provision: ProvisionMessage,
        password: String,
        deviceName: String
    ): Result {
        val aci = ServiceId.ACI.parseOrThrow(provision.aci, provision.aciBinary)
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
                accounts.forgetSessionsFromPreviousLink()

                accounts.saveIdentity(ProtocolDatabase.ACCOUNT_ID_TYPE_ACI, aciIdentity, aciRegistrationId)
                accounts.saveIdentity(ProtocolDatabase.ACCOUNT_ID_TYPE_PNI, pniIdentity, pniRegistrationId)
                accounts.saveCredentials(
                    provision.number,
                    aci.toString(),
                    provision.pni,
                    deviceId,
                    password
                )
                provision.profileKey?.let { accounts.saveProfileKey(it.toByteArray()) }
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
