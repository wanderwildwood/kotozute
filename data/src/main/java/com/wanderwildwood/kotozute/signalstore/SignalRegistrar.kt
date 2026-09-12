package com.wanderwildwood.kotozute.signalstore

import org.signal.core.models.ServiceId
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccess
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.util.KeyHelper
import org.signal.network.api.RegistrationApiV2
import org.signal.network.config.SignalServiceConfiguration
import org.signal.network.rest.SignalRestClient
import org.whispersystems.signalservice.api.util.CredentialsProvider
import timber.log.Timber
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.Base64
import java.util.Locale

/**
 * Registering this phone as a Signal account in its own right.
 *
 * The other way in is [DeviceLinker], which joins an account that already exists on another
 * phone. This is for the person who has no other phone: they are installing Signal for the
 * first time, and this is the only Signal they will have.
 *
 * ⚠ **This takes the number over.** Signal allows exactly one primary device per number, so
 * registering here deregisters Signal wherever else that number is registered and drops every
 * device linked to it. That is not a footnote, it is the decision the caller is making, and
 * the screen that drives this has to say so before the first request goes out.
 *
 * The sequence is the one Signal's own client follows and the one signal-cli follows, because
 * it is simply what the server requires:
 *
 *   1. open a verification session for the number
 *   2. answer whatever the server asks for -- in practice a captcha
 *   3. ask for the code, by SMS or by voice call
 *   4. submit the code
 *   5. register: identity keys, pre keys, and the account's attributes
 *
 * Steps two and three can repeat. The server decides whether a captcha is needed and says so
 * in [RegistrationApiV2.SessionMetadata.requestedInformation]; it is not something this can
 * predict, so the flow asks rather than assumes.
 *
 * There is no push challenge here. That shortcut wants an FCM token, and a Kompakt has no
 * Google Play Services to issue one, so the captcha is the only challenge this can answer.
 */
class SignalRegistrar internal constructor(
    private val configuration: SignalServiceConfiguration,
    private val userAgent: String,
    private val accounts: SignalAccountStore,
    private val signedPreKeys: (Int) -> SignalSignedPreKeyStore,
    private val kyberPreKeys: (Int) -> SignalKyberPreKeyStore
) {

    /**
     * Where the flow has got to.
     *
     * A session id is carried through rather than held in a field: registration can be
     * abandoned half way, the screen can be rebuilt under it, and a flow whose state lives in
     * the object cannot survive either.
     */
    sealed interface Step {
        /** The server wants a captcha solved before it will send a code. */
        data class NeedsCaptcha(val sessionId: String) : Step

        /** A code is on its way. [nextAttemptSeconds] is when another may be asked for. */
        data class CodeSent(val sessionId: String, val nextAttemptSeconds: Long?) : Step

        /** Registered. From here the account exists and the device is this phone. */
        data class Registered(val aci: String, val e164: String) : Step

        data class Failed(val reason: String) : Step
    }

    /** What the server names when it wants a captcha. */
    private val captchaRequested = "captcha"

    /**
     * Opens a session for [e164], and says what the server wants next.
     *
     * The number has to already be in E.164 -- a leading plus and digits. This does not try to
     * guess a country from a local-looking number, because guessing wrong registers a
     * different person's number, or fails in a way that reads like a server problem.
     */
    suspend fun begin(e164: String): Step {
        if (!E164.matches(e164)) return Step.Failed("that is not a phone number in +1... form")

        val api = anonymousApi()
        return when (val result = api.createVerificationSession(e164, null, null, null)) {
            is org.signal.libsignal.net.RequestResult.Success -> {
                val session = result.result
                Timber.i("signal register: session opened, wants %s", session.requestedInformation)
                when {
                    session.requestedInformation.contains(captchaRequested) ->
                        Step.NeedsCaptcha(session.id)
                    session.allowedToRequestCode -> requestCode(session.id, voice = false)
                    else -> Step.Failed("the server will not send a code yet")
                }
            }
            else -> Step.Failed("could not start registration: $result")
        }
    }

    /**
     * Hands back a solved captcha.
     *
     * The token comes from Signal's own captcha page, which ends by redirecting to a
     * `signalcaptcha://` URL with the answer in it. The caller loads that page and hands over
     * what it catches; nothing here knows how it was solved.
     */
    suspend fun submitCaptcha(sessionId: String, token: String): Step {
        val api = anonymousApi()
        return when (val result = api.updateVerificationSession(
            sessionId = sessionId,
            fcmToken = null,
            captchaToken = token,
            pushChallengeToken = null,
            mcc = null,
            mnc = null
        )) {
            is org.signal.libsignal.net.RequestResult.Success -> {
                val session = result.result
                if (session.allowedToRequestCode) requestCode(sessionId, voice = false)
                else Step.Failed("the captcha was accepted but the server still will not send a code")
            }
            else -> Step.Failed("the captcha was not accepted: $result")
        }
    }

    /**
     * Asks for the code.
     *
     * Voice is offered because SMS does not always arrive -- a number that cannot receive
     * texts at all is a normal thing to be registering, and without this that person is simply
     * stuck.
     */
    suspend fun requestCode(sessionId: String, voice: Boolean): Step {
        val api = anonymousApi()
        val transport =
            if (voice) RegistrationApiV2.VerificationCodeTransport.VOICE
            else RegistrationApiV2.VerificationCodeTransport.SMS

        return when (val result = api.requestVerificationCode(
            sessionId,
            Locale.getDefault(),
            // The SMS retriever is a Play Services API for reading the code without the
            // notification-listener permission. There is no Play Services here.
            false,
            transport
        )) {
            is org.signal.libsignal.net.RequestResult.Success ->
                Step.CodeSent(sessionId, result.result.nextVerificationAttempt)
            else -> Step.Failed("could not send a code: $result")
        }
    }

    /**
     * Submits the code and, if it is right, registers the account.
     *
     * These are one call because a verified session that is never registered is a dead end the
     * caller cannot do anything useful with, and leaving the two apart invites a screen that
     * says "verified" while the account does not exist.
     */
    suspend fun verifyAndRegister(sessionId: String, code: String, e164: String): Step {
        val api = anonymousApi()

        val verified = when (val result = api.submitVerificationCode(sessionId, code)) {
            is org.signal.libsignal.net.RequestResult.Success -> result.result
            else -> return Step.Failed("that code was not accepted: $result")
        }
        if (!verified.verified) return Step.Failed("that code was not accepted")

        return register(sessionId, e164)
    }

    private suspend fun register(sessionId: String, e164: String): Step {
        // Invented here and never sent anywhere but the registration request. It is half the
        // account credential from now on: lose it and the account is not recoverable from this
        // phone, only re-registerable.
        val password = randomPassword()

        val aciIdentity = IdentityKeyPair.generate()
        val pniIdentity = IdentityKeyPair.generate()
        val aciRegistrationId = KeyHelper.generateRegistrationId(false)
        val pniRegistrationId = KeyHelper.generateRegistrationId(false)

        val profileKey = ByteArray(32).also { SecureRandom().nextBytes(it) }

        val aciKeys = preKeyCollection(ProtocolDatabase.ACCOUNT_ID_TYPE_ACI, aciIdentity)
        val pniKeys = preKeyCollection(ProtocolDatabase.ACCOUNT_ID_TYPE_PNI, pniIdentity)

        val attributes = RegistrationApiV2.AccountAttributes(
            signalingKey = null,
            registrationId = aciRegistrationId,
            voice = false,
            video = false,
            // No push, so this device collects its own messages. Same as the linked case.
            fetchesMessages = true,
            registrationLock = null,
            unidentifiedAccessKey = UnidentifiedAccess.deriveAccessKeyFrom(ProfileKey(profileKey)),
            unrestrictedUnidentifiedAccess = false,
            // Being findable by phone number is the default Signal ships, and changing it
            // quietly during registration would be deciding something for someone.
            discoverableByPhoneNumber = true,
            capabilities = SignalCapabilities.forLinking(),
            name = null,
            pniRegistrationId = pniRegistrationId,
            recoveryPassword = null
        )

        val api = registeringApi(e164, password)
        return when (val result = api.registerAccount(
            sessionId = sessionId,
            recoveryPassword = null,
            e164 = e164,
            password = password,
            attributes = attributes,
            aciPreKeys = aciKeys,
            pniPreKeys = pniKeys,
            fcmToken = null,
            // There is no other device to transfer from; this is a new account.
            skipDeviceTransfer = true
        )) {
            is org.signal.libsignal.net.RequestResult.Success -> {
                val response = result.result
                // Written only now, and together. Everything above can be thrown away; from
                // here the account exists and these are the only way back to it.
                accounts.saveIdentity(ProtocolDatabase.ACCOUNT_ID_TYPE_ACI, aciIdentity, aciRegistrationId)
                accounts.saveIdentity(ProtocolDatabase.ACCOUNT_ID_TYPE_PNI, pniIdentity, pniRegistrationId)
                accounts.saveCredentials(
                    response.e164 ?: e164,
                    response.aci,
                    response.pni,
                    // A primary device is device 1, always. The server does not need to say so.
                    PRIMARY_DEVICE_ID,
                    password
                )
                accounts.saveProfileKey(profileKey)
                Timber.i("signal register: registered as primary")
                Step.Registered(response.aci, response.e164 ?: e164)
            }
            // ⚠ A number with a registration lock cannot be registered without the PIN, and
            // this app cannot supply one: Signal derives the lock token from a master key
            // recovered from SVR with the PIN, and none of that is implemented here -- both
            // `registrationLock` and `recoveryPassword` go up as null.
            //
            // So the honest thing is to say which wall this hit. Reported as "registration
            // refused: <opaque>" it reads as a bug or a bad code, and the natural response --
            // try again, ask for another code -- burns attempts against a number that will
            // refuse every one of them for the same reason. The server also says how long the
            // lock has to run, which is the one fact that decides what to do next.
            is org.signal.libsignal.net.RequestResult.NonSuccess -> {
                val error = result.error
                if (error is org.signal.network.api.RegistrationApiV2.RegisterAccountError.RegistrationLock) {
                    val days = TimeUnit.MILLISECONDS.toDays(error.data.timeRemaining)
                    Step.Failed(
                        "This number has a registration lock. Its PIN is needed to register it " +
                            "here, and this app cannot use one yet -- the lock has about $days " +
                            "day(s) left to run. Link this phone to the account instead."
                    )
                } else {
                    Step.Failed("registration refused: $result")
                }
            }
            else -> Step.Failed("registration refused: $result")
        }
    }

    /**
     * One signed pre key and one last-resort Kyber key per identity, stored as they are made.
     *
     * Stored, not merely counted -- the same trap [DeviceLinker] documents. Recording the ids
     * as active without keeping the keys leaves the server advertising a signed pre key whose
     * private half exists nowhere, and the first person to message this phone gets a message
     * that will not decrypt.
     */
    private fun preKeyCollection(
        accountIdType: Int,
        identity: IdentityKeyPair
    ): RegistrationApiV2.PreKeyCollection {
        val signedId = accounts.nextSignedPreKeyId(accountIdType)
        val kyberId = accounts.nextKyberPreKeyId(accountIdType)
        val signed = KeyUtilsForCheck.signedPreKey(signedId, identity.privateKey)
        val kyber = KeyUtilsForCheck.kyberPreKey(kyberId, identity.privateKey)

        signedPreKeys(accountIdType).storeSignedPreKey(signedId, signed)
        kyberPreKeys(accountIdType).storeLastResortKyberPreKey(kyberId, kyber)

        accounts.recordActiveSignedPreKey(accountIdType, signedId)
        accounts.recordActiveLastResortKyberPreKey(accountIdType, kyberId)
        return RegistrationApiV2.PreKeyCollection(identity.publicKey, signed, kyber)
    }

    /** Session calls carry no identity: there is no account yet to authenticate as. */
    private fun anonymousApi() = RegistrationApiV2(
        SignalRestClient(configuration, userAgent, null),
        false
    )

    /**
     * The registration call itself is authenticated, with the number and the password this
     * client just invented -- that pairing is what the server records as the account.
     */
    private fun registeringApi(e164: String, password: String) = RegistrationApiV2(
        SignalRestClient(
            configuration,
            userAgent,
            object : CredentialsProvider {
                override fun getAci(): ServiceId.ACI? = null
                override fun getPni(): ServiceId.PNI? = null
                override fun getE164(): String = e164
                override fun getDeviceId() = PRIMARY_DEVICE_ID
                override fun getPassword() = password
            }
        ),
        false
    )

    private fun randomPassword(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return Base64.getEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        /** Signal numbers the account's first device 1, and a primary is always that one. */
        const val PRIMARY_DEVICE_ID = 1

        /**
         * A phone number the server will accept: E.164, so a plus and up to fifteen digits.
         *
         * Checked here rather than trusted from the screen, because the failure it prevents is
         * not a rejected request -- it is registering a number that is not the one the person
         * meant, which cannot be undone from this side.
         */
        val E164 = Regex("""^\+[1-9]\d{6,14}$""")
    }
}
