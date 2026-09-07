package com.wanderwildwood.kotozute.signalstore

import org.signal.core.models.ServiceId
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.util.KeyHelper
import org.signal.network.api.RegistrationApiV2
import org.signal.network.config.SignalServiceConfiguration
import org.signal.network.rest.SignalRestClient
import org.whispersystems.signalservice.api.provisioning.ProvisioningSocket
import org.whispersystems.signalservice.api.util.CredentialsProvider
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
internal class DeviceLinker(
    private val configuration: SignalServiceConfiguration,
    private val userAgent: String,
    private val accounts: SignalAccountStore
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
     * Runs the whole exchange. Suspending, because the socket is: the URL arrives before the
     * provisioning message does, and the gap between them is however long it takes somebody to
     * pick up their phone and scan.
     */
    suspend fun link(deviceName: String, onUrl: UrlListener): Result {
        val provisioningKeys = IdentityKeyPair.generate()
        val password = generatePassword()

        var message: org.whispersystems.signalservice.internal.push.ProvisionMessage? = null
        var failure: String? = null

        ProvisioningSocket.start<Any>(
            ProvisioningSocket.Mode.Link(false),
            provisioningKeys,
            configuration,
            { id, t ->
                failure = t.message ?: t::class.java.simpleName
                Timber.w(t, "signal link: provisioning socket %d failed", id)
            }
        ) { socket ->
            onUrl.onUrl(socket.getProvisioningUrl())
            when (val decrypted = socket.getProvisioningMessageDecryptResult()) {
                is org.whispersystems.signalservice.internal.crypto.SecondaryProvisioningCipher.ProvisioningDecryptResult.Success<*> ->
                    message = decrypted.message as? org.whispersystems.signalservice.internal.push.ProvisionMessage
                else ->
                    // The reason only ever reaches the service layer's own log, which is why
                    // that is routed into Timber before any of this runs.
                    failure = "the provisioning message could not be decrypted"
            }
        }.use { /* closed as soon as the exchange ends, successfully or not */ }

        val provision = message ?: return Result.Failed(failure ?: "no provisioning message")
        return register(provision, password, deviceName)
    }

    private suspend fun register(
        provision: org.whispersystems.signalservice.internal.push.ProvisionMessage,
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
            RegistrationApiV2.AccountAttributes.Capabilities(false, false, false, false, false, false)
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
private fun org.whispersystems.signalservice.internal.push.ProvisionMessage.aciIdentityKeyPair() =
    IdentityKeyPair(
        org.signal.libsignal.protocol.IdentityKey(aciIdentityKeyPublic!!.toByteArray()),
        org.signal.libsignal.protocol.ecc.ECPrivateKey(aciIdentityKeyPrivate!!.toByteArray())
    )

private fun org.whispersystems.signalservice.internal.push.ProvisionMessage.pniIdentityKeyPair() =
    IdentityKeyPair(
        org.signal.libsignal.protocol.IdentityKey(pniIdentityKeyPublic!!.toByteArray()),
        org.signal.libsignal.protocol.ecc.ECPrivateKey(pniIdentityKeyPrivate!!.toByteArray())
    )
