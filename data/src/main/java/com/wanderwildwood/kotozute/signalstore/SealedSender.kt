package com.wanderwildwood.kotozute.signalstore

import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.signal.network.NetworkResult
import org.whispersystems.signalservice.api.crypto.SealedSenderAccess
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccess
import timber.log.Timber

/**
 * Sends without telling the server who is sending.
 *
 * Signal's envelope normally carries the sender's identity so the server can route the reply
 * and rate-limit. Sealed sender removes it: the sender is encrypted inside the envelope, and
 * the server is given only a token proving *someone* is allowed to send to this recipient.
 * What it protects is metadata -- who talks to whom, and how often -- which is the thing the
 * message encryption itself does not hide.
 *
 * It needs two things, and both can be missing:
 *
 * 1. **A sender certificate**, from the server, proving this account may send as itself. It is
 *    short-lived and fetched as needed.
 * 2. **The recipient's access key**, derived from their profile key -- which they only share
 *    with people they have chosen to. No profile key, no sealed sender to that person.
 *
 * When either is missing this returns null and the caller sends identified, which is what
 * Signal's own clients do in the same situation. Failing the send instead would trade a
 * metadata leak for a message that does not arrive, and that is not the better outcome.
 */
internal class SealedSender(
    private val connection: SignalConnection,
    private val contacts: SignalContactStore
) {

    /**
     * The certificate is cached for the process rather than fetched per message: a send is
     * already several round trips and this would add one more to each. It is refetched when
     * it expires, which the server decides.
     */
    @Volatile private var certificate: ByteArray? = null

    /**
     * @return the access to send with, or null to send identified.
     */
    fun accessFor(recipientAci: String): SealedSenderAccess? {
        val profileKeyBytes = contacts.profileKeyFor(recipientAci) ?: run {
            // Expected, not exceptional: it simply means this person has not shared their
            // profile with us. Logged at debug so it does not read as a fault.
            Timber.d("signal send: no profile key for the recipient; sending identified")
            return null
        }
        val cert = senderCertificate() ?: return null

        return try {
            val accessKey = UnidentifiedAccess.deriveAccessKeyFrom(ProfileKey(profileKeyBytes))
            SealedSenderAccess.forIndividual(UnidentifiedAccess(accessKey, cert, false))
        } catch (t: Throwable) {
            Timber.w(t, "signal send: could not build sealed sender access; sending identified")
            null
        }
    }

    private fun senderCertificate(): ByteArray? {
        certificate?.let { return it }
        // The phone-number-privacy variant (includeE164=false), not the plain one.
        //
        // A sender certificate is shown to the *recipient*, and the ordinary one carries the
        // sender's phone number. For anyone who has our number that changes nothing; for
        // anyone who knows us only by username or through a group it hands them a phone
        // number they did not have. Signal uses this variant for exactly that reason, and
        // there is no case where including the number is the more private choice.
        return when (val result = connection.certificates.getSenderCertificateForPhoneNumberPrivacy()) {
            is NetworkResult.Success -> result.result.also {
                certificate = it
                Timber.i("signal send: got a sender certificate")
            }
            else -> {
                Timber.w("signal send: no sender certificate (%s); sending identified", result)
                null
            }
        }
    }
}
