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
        val pending = contacts.needingProfile()
        if (pending.isEmpty()) return 0

        val learned = pending.mapNotNull { (aci, keyBytes) ->
            val serviceId = ServiceId.ACI.parseOrNull(aci) ?: return@mapNotNull null
            val name = runCatching { nameFor(serviceId, ProfileKey(keyBytes)) }
                .onFailure { Timber.w(it, "signal profile: could not fetch for a contact") }
                .getOrNull()
                ?: return@mapNotNull null
            SignalContactStore.Contact(aci = aci, e164 = null, name = name)
        }
        if (learned.isNotEmpty()) contacts.store(learned)
        Timber.i("signal profile: learned %d of %d names", learned.size, pending.size)
        return learned.size
    }

    private fun nameFor(aci: ServiceId.ACI, profileKey: ProfileKey): String? {
        // The API suspends, and this runs on a worker thread that owns itself, so blocking
        // here costs nothing and keeps every caller free of coroutines.
        val result = runBlocking { connection.profiles.getVersionedProfile(aci, profileKey, null) }
        if (result !is NetworkResult.Success) {
            Timber.d("signal profile: %s", result)
            return null
        }
        val cipher = ProfileCipher(profileKey)
        val decrypted = result.result.name?.let { encrypted ->
            runCatching {
                String(cipher.decrypt(android.util.Base64.decode(encrypted, android.util.Base64.DEFAULT)))
            }.getOrNull()
        } ?: return null

        // Given and family names are ONE field separated by a NUL byte, not by a space --
        // splitting on whitespace would break every name that contains one and would keep the
        // trailing padding the cipher leaves behind.
        val parts = decrypted.split(SEPARATOR).map { it.trim(PADDING) }
        val given = parts.getOrNull(0).orEmpty()
        val family = parts.getOrNull(1).orEmpty()
        return listOf(given, family).filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotBlank() }
    }

    companion object {
        /** The NUL that separates given from family name inside the encrypted blob. */
        private const val SEPARATOR = '\u0000'

        /** Profile fields are padded to a fixed length with the same byte. */
        private const val PADDING = '\u0000'
    }
}
