package com.wanderwildwood.kotozute.signalstore

import org.signal.core.models.ServiceId
import org.signal.network.config.SignalServiceConfiguration
import org.whispersystems.signalservice.api.SignalServiceMessageSender
import org.whispersystems.signalservice.api.SignalSessionLock
import org.whispersystems.signalservice.api.crypto.ContentHint
import org.whispersystems.signalservice.api.messages.SendMessageResult
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.message.MessageApi
import org.whispersystems.signalservice.api.util.CredentialsProvider
import org.whispersystems.signalservice.internal.push.PushServiceSocket
import org.whispersystems.signalservice.api.keys.PreKeyRepository
import timber.log.Timber
import java.util.Optional
import java.util.concurrent.Executors

/**
 * Sends messages from this device.
 *
 * The other half of what the bridge did. A linked device sends on its own authority -- the
 * primary is not consulted and is not in the path -- which is why this needs the same protocol
 * store the receive side uses: sending builds sessions, consumes the recipient's pre keys and
 * writes ratchet state, exactly as receiving does.
 */
internal class SignalSender(
    private val configuration: SignalServiceConfiguration,
    private val userAgent: String,
    private val accounts: SignalAccountStore,
    private val db: ProtocolDatabase,
    private val protocol: SignalDataStore,
    private val connection: SignalConnection
) {

    /** The store's own lock, for the same reason the receiver uses it. See [SignalReceiver]. */
    private val sessionLock = SignalSessionLock {
        db.lock.lock()
        SignalSessionLock.Lock { db.lock.unlock() }
    }

    private val credentials = object : CredentialsProvider {
        override fun getAci(): ServiceId.ACI? = ServiceId.ACI.parseOrNull(accounts.credentials().aci)
        override fun getPni(): ServiceId.PNI? = ServiceId.PNI.parseOrNull(accounts.credentials().pni)
        override fun getE164(): String? = accounts.credentials().e164
        override fun getDeviceId(): Int = accounts.credentials().deviceId
        override fun getPassword(): String? = accounts.credentials().password
    }

    private val sender: SignalServiceMessageSender by lazy {
        val socket = PushServiceSocket(configuration, credentials, userAgent, true)
        val aci = credentials.aci ?: error("not linked")
        SignalServiceMessageSender(
            socket,
            protocol,
            sessionLock,
            MessageApi(connection.authenticated, connection.unauthenticated),
            connection.keys,
            Optional.empty(),
            // One thread. Sends against one recipient must not interleave: two in flight
            // against the same device race on the ratchet, and the loser produces a message
            // the far end cannot decrypt.
            Executors.newSingleThreadExecutor(),
            MAX_ENVELOPE_SIZE,
            MAX_INCREMENTAL_MACS_PER_ENVELOPE,
            { true },
            PreKeyRepository(
                connection.keys,
                protocol.aci(),
                aci.toProtocolAddress(credentials.deviceId),
                sessionLock,
                // Runs the block directly. The hook exists so a client can defer the
                // bookkeeping that follows an identity change -- storage sync, live recipient
                // updates -- while a batch of sessions is built. This app has none of that
                // bookkeeping, so there is nothing to defer. signal-cli passes the equivalent.
                PreKeyRepository.BatchHelper { it.run() }
            )
        )
    }

    sealed interface Result {
        data class Sent(val timestamp: Long) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Sends [body] to one recipient.
     *
     * The timestamp is the message's identity, not a decoration: it is half of the
     * `(author, timestamp)` pair every other device uses to recognise this message, including
     * our own other devices when this send comes back to them as a sync. So it is generated
     * once, here, and returned -- not read back from anything.
     */
    fun send(recipient: ServiceId, body: String): Result {
        val timestamp = System.currentTimeMillis()
        val message = SignalServiceDataMessage.newBuilder()
            .withBody(body)
            .withTimestamp(timestamp)
            .build()

        return try {
            val result: SendMessageResult = sender.sendDataMessage(
                SignalServiceAddress(recipient),
                // No sealed sender. It needs a sender certificate fetched from the server and
                // an access key derived from the recipient's profile key -- neither of which
                // this device fetches yet. Sending identified is correct, just less private:
                // the server learns who sent it, which it would anyway for a first message.
                null,
                ContentHint.RESENDABLE,
                message,
                SignalServiceMessageSender.IndividualSendEvents.EMPTY,
                false,
                false
            )
            when {
                result.isSuccess -> {
                    Timber.i("signal send: delivered ts=%d", timestamp)
                    Result.Sent(timestamp)
                }
                // Worth separating. An identity failure is not a network problem: the
                // recipient's safety number changed, and retrying sends to a key we have
                // already refused to trust.
                result.identityFailure != null -> Result.Failed("identity changed for $recipient")
                result.isUnregisteredFailure -> Result.Failed("$recipient is not registered")
                result.isNetworkFailure -> Result.Failed("network failure sending to $recipient")
                result.isInvalidPreKeyFailure -> Result.Failed("$recipient has an unusable pre key")
                result.rateLimitFailure != null -> Result.Failed("rate limited")
                result.proofRequiredFailure != null -> Result.Failed("the server wants a proof of humanity")
                else -> Result.Failed("send failed for an unreported reason")
            }
        } catch (t: Throwable) {
            Timber.w(t, "signal send: threw")
            Result.Failed(t.message ?: t::class.java.simpleName)
        }
    }

    companion object {
        /** signal-cli's values. 0 means the server's own limit applies. */
        private const val MAX_ENVELOPE_SIZE = 0L
        private const val MAX_INCREMENTAL_MACS_PER_ENVELOPE = 10
    }
}
