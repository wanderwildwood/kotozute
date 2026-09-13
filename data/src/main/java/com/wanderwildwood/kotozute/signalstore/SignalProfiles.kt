package com.wanderwildwood.kotozute.signalstore

import kotlinx.coroutines.runBlocking
import org.signal.core.models.ServiceId
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.signal.network.NetworkResult
import org.whispersystems.signalservice.api.crypto.ProfileCipher
import org.whispersystems.signalservice.api.crypto.SealedSenderAccess
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
     * The same sealed sender access a message would be sent with.
     *
     * A profile read is a request *about* somebody, and sent authenticated it names both
     * parties to the server: this account, and the person it is asking after. Doing that for
     * every name lookup hands over an attributable running record of who this account knows --
     * and [refreshMissingNames] fires up to twenty-five of them after every received batch.
     *
     * Signal attaches the recipient's sealed sender access to profile reads for exactly this
     * reason (`RetrieveProfileJob` and `ProfileUtil`, both via
     * `SealedSenderAccessUtil.getSealedSenderAccessFor`), which sends them over the
     * unauthenticated socket instead.
     */
    private val sealedSender = SealedSender(connection, contacts)

    /**
     * Fills in names for every contact that has a profile key and no name yet.
     *
     * @return how many names were learned.
     */
    fun refreshMissingNames(): Int {
        // Nameless people first, then whoever has gone longest without being looked at.
        // Bounded per pass: each is a round trip and this runs after a received batch.
        // ⚠ Nothing at all while the server has told us to wait. This pass runs after every
        // received batch and asks for up to twenty-five profiles; carrying on through a rate
        // limit meant twenty-five more doomed requests each time, which deepens the limit,
        // advances nobody, and throws away the one useful thing the server said -- how long to
        // wait. Signal stops the whole batch on a 429 and backs off for exactly that long
        // (`ProfileRepository` raises `RateLimitException` with the retry-after, and
        // `RetrieveProfileJob` turns it into `RetryLaterException(retryAfter)`).
        val waitUntil = rateLimitedUntil
        if (System.currentTimeMillis() < waitUntil) {
            Timber.i(
                "signal profile: rate limited for another %d s; not asking",
                (waitUntil - System.currentTimeMillis()) / 1000
            )
            return 0
        }

        val staleBefore = System.currentTimeMillis() - PROFILE_MAX_AGE_MS
        val pending = contacts.needingProfile(staleBefore, PROFILES_PER_PASS)
        if (pending.isEmpty()) return 0

        val learned = mutableListOf<SignalContactStore.Contact>()
        var limited: RateLimited? = null

        for ((aci, keyBytes) in pending) {
            // ⚠ Any service id, not only an account id. The query already offers people known
            // by their phone-number identity -- 76 of them on one real account -- and this
            // threw every one away by insisting on an ACI. Signal does not: `RetrieveProfileJob`
            // builds its requests with `recipient.requireServiceId()`, whichever kind it is.
            //
            // The fetch differs by kind, which is the reason this needs saying rather than a
            // one-word change. A *versioned* profile is keyed by account id and the library
            // will not take anything else. An *unversioned* one takes any service id, and its
            // name is encrypted with the same profile key -- so somebody known only by a
            // phone-number identity, whose key arrived from a group or a message, can still be
            // named. Without a key neither call yields a name, and those are the rows that
            // stay unnamed.
            val serviceId = ServiceId.parseOrNull(aci) ?: continue
            val key = ProfileKey(keyBytes)
            val profile = try {
                fetch(serviceId, key, sealedSender.accessFor(aci))
            } catch (e: RateLimited) {
                // Terminal for the whole pass, not for this one contact: the limit is on the
                // account, so the next twenty-four requests would be refused too. Whatever was
                // already learned is kept, as upstream keeps its successes.
                limited = e
                break
            } catch (t: Throwable) {
                Timber.w(t, "signal profile: could not fetch for a contact")
                null
            } ?: continue

            // Whether they accept sealed sender, said by them rather than inferred from how a
            // send happened to go. This is the authoritative answer and the cheapest one: the
            // profile is already being fetched, and the verifier in it is exactly the field
            // that settles it. Without this the only source was trial and error, which costs
            // a wasted round trip per person and cannot tell "refused" from "we guessed".
            contacts.setSealedSenderMode(aci, sealedSenderMode(key, profile))

            // Asked, whatever came back. Without this the same people are asked again on every
            // batch and the per-pass budget never reaches anybody else.
            runCatching { contacts.markProfileFetched(aci) }
                .onFailure { Timber.w(it, "signal profile: could not note the fetch") }

            profile.name?.let {
                learned += SignalContactStore.Contact(serviceId = aci, e164 = null, name = it)
            }
        }

        if (learned.isNotEmpty()) contacts.store(learned)

        limited?.let { e ->
            rateLimitedUntil = System.currentTimeMillis() + e.waitMs
            Timber.w(
                "signal profile: the server is rate limiting us; waiting %d s before asking again",
                e.waitMs / 1000
            )
        }

        Timber.i("signal profile: learned %d of %d names", learned.size, pending.size)
        return learned.size
    }

    /**
     * The server has refused for now, and said for how long.
     *
     * Carried as an exception because it has to stop the pass from inside the fetch, and
     * because a 429 is not this contact's answer -- it is the account's, and every remaining
     * request in the pass would get the same one.
     */
    private class RateLimited(val waitMs: Long) : Exception("rate limited")

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

    /**
     * @param access the recipient's sealed sender access, or null to ask authenticated.
     */
    private fun fetch(
        serviceId: ServiceId,
        profileKey: ProfileKey,
        access: SealedSenderAccess?
    ): Profile? {
        // The API suspends, and this runs on a worker thread that owns itself, so blocking
        // here costs nothing and keeps every caller free of coroutines.
        //
        // ⚠ The third argument was a hard-coded null, which is not "no preference": the
        // library reads null as "use the authenticated socket" and sends the request as this
        // account. With an access it goes over the unauthenticated socket and falls back to
        // the authenticated one only on a 401 -- `ProfileApi.getVersionedProfile` and
        // `getUnversionedProfile` both branch on exactly that. So null here still works, and
        // costs the metadata of every name this app has ever looked up.
        val result = runBlocking {
            when (serviceId) {
                is ServiceId.ACI -> connection.profiles.getVersionedProfile(serviceId, profileKey, access)
                else -> connection.profiles.getUnversionedProfile(serviceId, access)
            }
        }
        if (result is NetworkResult.StatusCodeError && result.code == 429) {
            // The server's own answer for how long, when it gave one. Signal reads the same
            // `retry-after` header and waits exactly that long rather than guessing.
            val wait = result.retryAfter()?.inWholeMilliseconds ?: DEFAULT_RATE_LIMIT_WAIT_MS
            throw RateLimited(wait.coerceIn(MIN_RATE_LIMIT_WAIT_MS, MAX_RATE_LIMIT_WAIT_MS))
        }
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
        // Not a plain given-then-family join: see [ProfileNames.joined], which puts a CJKV
        // name in the order its owner writes it.
        val name = ProfileNames.joined(given, family)
        return Profile(name, profile.unidentifiedAccess, profile.isUnrestrictedUnidentifiedAccess)
    }

    companion object {
        /**
         * How long a profile is believed before it is worth asking again.
         *
         * A name is not fixed -- people change what they call themselves -- and eligibility
         * used to be "has no name at all", so the first name ever learned was the last.
         *
         * One day, which is Signal's: `RetrieveProfileJob` refetches profiles whose last fetch
         * is older than `TimeUnit.DAYS.toMillis(1)`. This said seven, which was a number picked
         * here rather than taken from anywhere -- a contact who changed their name took a week
         * to update, against a day on their own phone.
         */
        private val PROFILE_MAX_AGE_MS = java.util.concurrent.TimeUnit.DAYS.toMillis(1)

        /** At most this many round trips after any one batch. */
        private const val PROFILES_PER_PASS = 25

        /**
         * When the server will next take a profile request from us.
         *
         * ⚠ On the companion, not the instance, and that is deliberate: a [SignalProfiles] is
         * built fresh for every pass (`SignalStore` constructs one per received batch), so an
         * instance field would be forgotten immediately and the back-off would never happen.
         * The same lesson as the sender certificate cache.
         *
         * In memory only. A restart forgets it and asks once more, which costs one refusal and
         * is the right trade against persisting a wait that may already have elapsed.
         */
        @Volatile private var rateLimitedUntil = 0L

        /** When the server refuses without saying for how long. */
        private val DEFAULT_RATE_LIMIT_WAIT_MS = java.util.concurrent.TimeUnit.MINUTES.toMillis(5)

        /**
         * Bounds on what the server's `retry-after` is allowed to mean here.
         *
         * A header is not a promise: a missing or absurd value should not leave this account
         * unable to learn a name for a week, nor let it retry in a second and deepen the limit.
         */
        private val MIN_RATE_LIMIT_WAIT_MS = java.util.concurrent.TimeUnit.SECONDS.toMillis(30)
        private val MAX_RATE_LIMIT_WAIT_MS = java.util.concurrent.TimeUnit.HOURS.toMillis(12)

        /** The NUL that separates given from family name inside the encrypted blob. */
        private const val SEPARATOR = '\u0000'

        /** Profile fields are padded to a fixed length with the same byte. */
        private const val PADDING = '\u0000'
    }
}
