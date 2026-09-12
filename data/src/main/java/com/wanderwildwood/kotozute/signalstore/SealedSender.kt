package com.wanderwildwood.kotozute.signalstore

import org.signal.libsignal.metadata.certificate.SenderCertificate
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.signal.network.NetworkResult
import org.whispersystems.signalservice.api.crypto.SealedSenderAccess
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccess
import timber.log.Timber
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Sends without telling the server who is sending.
 *
 * Signal's envelope normally carries the sender's identity so the server can route the reply
 * and rate-limit. Sealed sender removes it: the sender is encrypted inside the envelope, and
 * the server is given only a token proving *someone* is allowed to send to this recipient.
 * What it protects is metadata -- who talks to whom, and how often -- which is the thing the
 * message encryption itself does not hide.
 *
 * It needs two things:
 *
 * 1. **A sender certificate**, from the server, proving this account may send as itself. It is
 *    short-lived, so it is cached with its expiry rather than for ever.
 * 2. **An access key for the recipient**, normally derived from their profile key -- which
 *    they only share with people they have chosen to.
 *
 * The second one used to end the matter: no profile key, no sealed sender, permanently. That
 * is not what Signal does, and it cost the protection in exactly the common case. A great many
 * people have sealed sender set to "anyone", for whom *any* key is accepted; and the only way
 * to find that out is to try. So a person we know nothing about is sent to with a random key,
 * and what happens next is written down -- see [recordOutcome]. The library falls back to an
 * identified send by itself when the server refuses, so the cost of guessing wrong is one
 * extra request, and the cost of never guessing was never protecting anybody's metadata.
 */
internal class SealedSender(
    private val connection: SignalConnection,
    private val contacts: SignalContactStore
) {

    /**
     * @return the access to send with, or null to send identified.
     */
    fun accessFor(recipientAci: String): SealedSenderAccess? {
        val mode = contacts.sealedSenderModeFor(recipientAci)
        val profileKeyBytes = contacts.profileKeyFor(recipientAci)
        val accessKey = when (keyFor(mode, profileKeyBytes != null)) {
            Key.None -> return null
            Key.Random -> randomKey()
            Key.Derived -> try {
                UnidentifiedAccess.deriveAccessKeyFrom(ProfileKey(profileKeyBytes!!))
            } catch (t: Throwable) {
                Timber.w(t, "signal send: could not derive an access key; sending identified")
                return null
            }
        }

        val cert = senderCertificate() ?: return null
        return try {
            SealedSenderAccess.forIndividual(UnidentifiedAccess(accessKey, cert, false))
        } catch (t: Throwable) {
            Timber.w(t, "signal send: could not build sealed sender access; sending identified")
            null
        }
    }

    /**
     * Remember how a send to this person actually went.
     *
     * Ported from `IndividualSendJob`, whose reasoning is worth keeping: a send that went out
     * sealed *without* a profile key proves they accept anything, which is worth knowing
     * because it means every future message to them can be sealed too. A send that had to fall
     * back proves the opposite, and stops this device guessing at them for ever.
     *
     * @param unidentified whether the message actually went out sealed.
     */
    fun recordOutcome(recipientAci: String, unidentified: Boolean) {
        val mode = contacts.sealedSenderModeFor(recipientAci)
        val had = contacts.profileKeyFor(recipientAci) != null
        val learned = modeAfter(mode, unidentified, had) ?: return
        contacts.setSealedSenderMode(recipientAci, learned)
    }

    /** What to send with. Separated from the sending so the rule itself can be tested. */
    internal enum class Key { None, Random, Derived }

    private fun randomKey(): ByteArray = ByteArray(ACCESS_KEY_SIZE).also { SecureRandom().nextBytes(it) }

    /**
     * The certificate, fetched when there is not a usable one already.
     *
     * ⚠ The cache is on the companion, not the instance, and that is the whole point of it:
     * a [SignalSender] -- and with it this class -- is built fresh for every single operation,
     * so an instance-level cache was never read twice and every message paid for its own
     * certificate fetch. A round trip per message, invisible except as latency.
     */
    private fun senderCertificate(): ByteArray? {
        synchronized(Companion) {
            cached?.let { if (System.currentTimeMillis() < cachedUntil) return it }
        }
        // The phone-number-privacy variant (includeE164=false), not the plain one.
        //
        // A sender certificate is shown to the *recipient*, and the ordinary one carries the
        // sender's phone number. For anyone who has our number that changes nothing; for
        // anyone who knows us only by username or through a group it hands them a phone
        // number they did not have. Signal uses this variant for exactly that reason, and
        // there is no case where including the number is the more private choice.
        return when (val result = connection.certificates.getSenderCertificateForPhoneNumberPrivacy()) {
            is NetworkResult.Success -> result.result.also { bytes ->
                synchronized(Companion) {
                    cached = bytes
                    // Its own expiry, less a margin. A certificate is valid for days, so this
                    // is not a fetch anyone will notice; using one past its expiry, on the
                    // other hand, is a message the recipient's client discards.
                    // The sooner of "nearly expired" and "a day old". Signal replaces its
                    // certificate every day on a timer (`RotateSenderCertificateListener`,
                    // INTERVAL = 1 day) rather than waiting for expiry, and a certificate is
                    // good for about a week -- so holding one to the end means carrying up to
                    // six days of whatever it asserts. After a number change that is six days
                    // of a certificate describing the old one: still signed, still unexpired,
                    // and no longer true.
                    cachedUntil = minOf(
                        expiryOf(bytes) - RENEW_MARGIN_MS,
                        System.currentTimeMillis() + MAX_CERTIFICATE_AGE_MS
                    )
                }
                Timber.i("signal send: got a sender certificate")
            }
            else -> {
                Timber.w("signal send: no sender certificate (%s); sending identified", result)
                null
            }
        }
    }

    /**
     * When the server says this certificate stops being good.
     *
     * Read from the certificate rather than guessed at. If it cannot be parsed the answer is
     * "already expired", which costs a fetch next time and never sends with something bad.
     */
    private fun expiryOf(bytes: ByteArray): Long = try {
        SenderCertificate(bytes).expiration
    } catch (t: Throwable) {
        Timber.w(t, "signal send: could not read the certificate's expiry")
        0L
    }

    companion object {

        /**
         * Which access key a recipient in this state should be sent with.
         *
         * Signal's `getTargetUnidentifiedAccessKey`, case for case. The one worth naming is
         * unknown-with-no-profile-key: a random key, because a great many accounts accept
         * anything and trying is the only way to find out. Returning nothing there -- which is
         * what this used to do -- gave up the protection for everyone we had not been given a
         * profile key by.
         */
        internal fun keyFor(mode: Int, hasProfileKey: Boolean): Key = when {
            // Learned, not assumed: a send to this person already had to fall back. Guessing
            // again would be a wasted round trip on every message to them.
            mode == SEALED_SENDER_DISABLED -> Key.None
            mode == SEALED_SENDER_UNRESTRICTED -> Key.Random
            hasProfileKey -> Key.Derived
            // Enabled means their key is required and we do not have it. A guess is refused.
            mode == SEALED_SENDER_ENABLED -> Key.None
            else -> Key.Random
        }

        /**
         * What a send's outcome teaches, or null when it teaches nothing.
         *
         * Ported from `IndividualSendJob`. A send that went out sealed *without* a profile key
         * proves they accept anything; one that had to fall back proves the opposite and stops
         * this device guessing at them for ever.
         */
        internal fun modeAfter(mode: Int, unidentified: Boolean, hasProfileKey: Boolean): Int? = when {
            unidentified && mode == SEALED_SENDER_UNKNOWN && !hasProfileKey -> SEALED_SENDER_UNRESTRICTED
            unidentified && mode == SEALED_SENDER_UNKNOWN -> SEALED_SENDER_ENABLED
            !unidentified && mode != SEALED_SENDER_DISABLED -> SEALED_SENDER_DISABLED
            else -> null
        }

        /** Shared by every [SealedSender] in the process, because they are all the same account. */
        @Volatile private var cached: ByteArray? = null
        @Volatile private var cachedUntil = 0L

        /** Signal's access keys are 16 bytes, derived or not. */
        private const val ACCESS_KEY_SIZE = 16

        /**
         * Renewed this long before it expires, so a send never races the deadline -- the clock
         * here and the server's need not agree to the minute.
         */
        private val RENEW_MARGIN_MS = TimeUnit.HOURS.toMillis(1)

        /** Signal's `RotateSenderCertificateListener.INTERVAL`. */
        private val MAX_CERTIFICATE_AGE_MS = TimeUnit.DAYS.toMillis(1)
    }
}
