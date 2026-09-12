package com.wanderwildwood.kotozute.signalstore

import kotlinx.coroutines.runBlocking
import org.signal.core.models.ServiceId
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.signal.network.NetworkResult
import org.whispersystems.signalservice.api.crypto.ProfileCipher
import timber.log.Timber

/**
 * Fetches and decrypts profiles, which is where a person's name actually lives.
 *
 * The contacts sync carries a `name` field, and on a modern account it is empty: clients have
 * largely stopped filling it and put the name in the profile instead. So the sync is what
 * supplies the profile *keys*, and this is what turns them into names.
 *
 * The server never sees any of it. A profile is stored encrypted under a key held only by the
 * people it has been shared with, which is why a linked device cannot simply ask for a name,
 * and why the key has to come from the primary first.
 */
internal class SignalProfiles(
    private val connection: SignalConnection,
    private val contacts: SignalContactStore
) {

    /**
     * Fills in names for every contact that has a profile key and no name yet.
     *
     * @return how many names were learned.
     */
    fun refreshMissingNames(): Int {
        // Nameless people first, then whoever has gone longest without being looked at.
        // Bounded per pass: each is a round trip and this runs after a received batch.
        val staleBefore = System.currentTimeMillis() - PROFILE_MAX_AGE_MS
        val pending = contacts.needingProfile(staleBefore, PROFILES_PER_PASS)
        if (pending.isEmpty()) return 0

        val learned = pending.mapNotNull { (aci, keyBytes) ->
            val serviceId = ServiceId.ACI.parseOrNull(aci) ?: return@mapNotNull null
            val key = ProfileKey(keyBytes)
            val profile = runCatching { fetch(serviceId, key) }
                .onFailure { Timber.w(it, "signal profile: could not fetch for a contact") }
                .getOrNull()
                ?: return@mapNotNull null

            // Whether they accept sealed sender, said by them rather than inferred from how a
            // send happened to go. This is the authoritative answer and the cheapest one: the
            // profile is already being fetched, and the verifier in it is exactly the field
            // that settles it. Without this the only source was trial and error, which costs
            // a wasted round trip per person and cannot tell "refused" from "we guessed".
            contacts.setSealedSenderMode(aci, sealedSenderMode(key, profile))

            profile.name?.let { SignalContactStore.Contact(serviceId = aci, e164 = null, name = it) }
        }
        if (learned.isNotEmpty()) contacts.store(learned)
        Timber.i("signal profile: learned %d of %d names", learned.size, pending.size)
        return learned.size
    }

    /** A fetched profile, as much of it as this app uses. */
    private data class Profile(
        val name: String?,
        /** The proof that they accept sealed sender, or null when they do not offer one. */
        val unidentifiedAccessVerifier: String?,
        /** They accept it from anybody, key or no key. */
        val unrestricted: Boolean
    )

    /**
     * Signal's `deriveUnidentifiedAccessMode`, unchanged.
     *
     * The verifier is the deciding field in every branch: an account that offers none is not
     * accepting sealed sender at all, and one that offers a verifier our key cannot check is
     * one whose key we no longer hold.
     */
    private fun sealedSenderMode(profileKey: ProfileKey, profile: Profile): Int {
        val verifier = profile.unidentifiedAccessVerifier ?: return SEALED_SENDER_DISABLED
        if (profile.unrestricted) return SEALED_SENDER_UNRESTRICTED
        val verified = runCatching {
            ProfileCipher(profileKey).verifyUnidentifiedAccess(
                android.util.Base64.decode(verifier, android.util.Base64.DEFAULT)
            )
        }.getOrDefault(false)
        return if (verified) SEALED_SENDER_ENABLED else SEALED_SENDER_DISABLED
    }

    private fun fetch(aci: ServiceId.ACI, profileKey: ProfileKey): Profile? {
        // The API suspends, and this runs on a worker thread that owns itself, so blocking
        // here costs nothing and keeps every caller free of coroutines.
        val result = runBlocking { connection.profiles.getVersionedProfile(aci, profileKey, null) }
        if (result !is NetworkResult.Success) {
            Timber.d("signal profile: %s", result)
            return null
        }
        val profile = result.result
        val cipher = ProfileCipher(profileKey)
        val decrypted = profile.name?.let { encrypted ->
            runCatching {
                String(cipher.decrypt(android.util.Base64.decode(encrypted, android.util.Base64.DEFAULT)))
            }.getOrNull()
        }
            // A profile with no readable name is still a profile, and what it says about
            // sealed sender is worth keeping. Returning null here threw that away.
            ?: return Profile(null, profile.unidentifiedAccess, profile.isUnrestrictedUnidentifiedAccess)

        // Given and family names are ONE field separated by a NUL byte, not by a space --
        // splitting on whitespace would break every name that contains one and would keep the
        // trailing padding the cipher leaves behind.
        val parts = decrypted.split(SEPARATOR).map { it.trim(PADDING) }
        val given = parts.getOrNull(0).orEmpty()
        val family = parts.getOrNull(1).orEmpty()
        val name = listOf(given, family).filter { it.isNotBlank() }
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
        return Profile(name, profile.unidentifiedAccess, profile.isUnrestrictedUnidentifiedAccess)
    }

    companion object {
        /**
         * How long a profile is believed before it is worth asking again.
         *
         * A name is not fixed -- people change what they call themselves -- and eligibility
         * used to be "has no name at all", so the first name ever learned was the last.
         */
        private val PROFILE_MAX_AGE_MS = java.util.concurrent.TimeUnit.DAYS.toMillis(7)

        /** At most this many round trips after any one batch. */
        private const val PROFILES_PER_PASS = 25

        /** The NUL that separates given from family name inside the encrypted blob. */
        private const val SEPARATOR = '\u0000'

        /** Profile fields are padded to a fixed length with the same byte. */
        private const val PADDING = '\u0000'
    }
}
