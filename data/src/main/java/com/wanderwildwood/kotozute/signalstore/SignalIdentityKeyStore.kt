package com.wanderwildwood.kotozute.signalstore

import org.signal.core.models.ServiceId
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.state.IdentityKeyStore
import timber.log.Timber

/**
 * Who we are, and who we believe everyone else to be.
 *
 * Ported from signal-cli's `IdentityKeyStore` (GPL-3.0, as is this app), including its trust
 * policy, which is the part worth being careful about rather than inventing.
 *
 * libsignal calls every method here from native code, on whatever thread it happens to be on,
 * often in the middle of a cipher operation. Hence the single reentrant lock held by
 * [ProtocolDatabase] rather than anything finer-grained, and hence SQLite rather than Realm.
 */
internal class SignalIdentityKeyStore(
    private val db: ProtocolDatabase,
    private val accountIdType: Int
) : IdentityKeyStore {

    /**
     * The account's own key pair, stored as its two halves because that is how the
     * ProvisionMessage delivers them and how signal-cli keeps them.
     */
    override fun getIdentityKeyPair(): IdentityKeyPair = db.lock.withLockReentrant {
        db.readableDatabase.rawQuery(
            "SELECT identity_public, identity_private FROM account_identity WHERE account_id_type = ?",
            arrayOf(accountIdType.toString())
        ).use { c ->
            check(c.moveToFirst()) { "no account_identity row for type $accountIdType" }
            val public = c.getBlob(0)
            val private = c.getBlob(1)
            checkNotNull(public) { "identity key not set: this device has not been linked" }
            IdentityKeyPair(IdentityKey(public), org.signal.libsignal.protocol.ecc.ECPrivateKey(private))
        }
    }

    override fun getLocalRegistrationId(): Int = db.lock.withLockReentrant {
        db.readableDatabase.rawQuery(
            "SELECT registration_id FROM account_identity WHERE account_id_type = ?",
            arrayOf(accountIdType.toString())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /**
     * Records a peer's identity key.
     *
     * Returns whether this replaced a different key, which is what libsignal uses to decide
     * that a session must be archived. Getting that answer wrong in the "unchanged" direction
     * leaves a session encrypting to a key the peer no longer holds.
     */
    override fun saveIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey
    ): IdentityKeyStore.IdentityChange = db.lock.withLockReentrant {
        val name = address.name
        val existing = loadIdentityKey(name)
        when {
            existing == null -> {
                insertIdentity(name, identityKey, TRUSTED_UNVERIFIED)
                IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
            }
            existing == identityKey -> IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
            else -> {
                // A changed key is recorded untrusted. The safety number has changed and the
                // person deserves to be told before anything else is sent to it.
                insertIdentity(name, identityKey, UNTRUSTED)
                Timber.i("signal store: identity changed for a peer; recorded untrusted")
                IdentityKeyStore.IdentityChange.REPLACED_EXISTING
            }
        }
    }

    /**
     * signal-cli's policy, kept deliberately rather than simplified.
     *
     * The asymmetry between directions is the whole of it: a first sighting is trusted on
     * faith, a *changed* key blocks receiving outright but is merely recorded when sending, so
     * the send fails at the trust check rather than silently going to a stranger.
     */
    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction
    ): Boolean = db.lock.withLockReentrant {
        val name = address.name
        var known = loadIdentity(name)
        if (known == null) {
            insertIdentity(name, identityKey, TRUSTED_UNVERIFIED)
            known = loadIdentity(name)
        } else if (known.key != identityKey) {
            if (direction == IdentityKeyStore.Direction.SENDING) {
                insertIdentity(name, identityKey, UNTRUSTED)
                known = loadIdentity(name)
            } else {
                return@withLockReentrant false
            }
        }
        known != null && known.trustLevel > UNTRUSTED
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        db.lock.withLockReentrant { loadIdentityKey(address.name) }

    // --- storage ---------------------------------------------------------------------------

    private class Known(val key: IdentityKey, val trustLevel: Int)

    /**
     * What is known about a peer's key: the safety number to read aloud, and whether the key
     * is still the one that was accepted.
     *
     * @return null when nobody has ever messaged this address, which is not the same as a
     *   changed key and must not be shown as one.
     */
    fun identityFor(address: String, selfServiceId: ServiceId): Identity? = db.lock.withLockReentrant {
        val known = loadIdentity(address) ?: return@withLockReentrant null
        val peer = ServiceId.parseOrNull(address) ?: return@withLockReentrant null
        val mine = identityKeyPair.publicKey

        // Version 2, service-id bytes, 5200 iterations -- the same numbers Signal's own
        // clients use. Any of the three differing produces a number that is stable, plausible
        // and will not match what the other person is reading off their screen, which defeats
        // the entire purpose of comparing them.
        val fingerprint = NumericFingerprintGenerator(5200).createFor(
            2,
            selfServiceId.toByteArray(), mine,
            peer.toByteArray(), known.key
        )
        Identity(fingerprint.displayableFingerprint.displayText, known.trustLevel)
    }

    /**
     * Accepts a peer's current key, so messages can be sent to them again.
     *
     * Only ever called because a person looked at a changed safety number and said yes. It
     * records TRUSTED_UNVERIFIED rather than TRUSTED_VERIFIED: they have chosen to proceed,
     * which is not the same as having compared the number with the person in front of them,
     * and claiming the stronger of the two would be the app putting words in their mouth.
     *
     * @return false if there is no key stored for the address, so a caller cannot report
     *   success for something that did not happen.
     */
    fun acceptIdentity(address: String): Boolean = db.lock.withLockReentrant {
        val known = loadIdentity(address) ?: return@withLockReentrant false
        insertIdentity(address, known.key, TRUSTED_UNVERIFIED)
        Timber.i("signal store: accepted a changed identity for a peer")
        true
    }

    data class Identity(val safetyNumber: String, val trustLevel: Int)

    private fun loadIdentity(address: String): Known? =
        db.readableDatabase.rawQuery(
            "SELECT identity_key, trust_level FROM identity WHERE address = ?",
            arrayOf(address)
        ).use { c ->
            if (c.moveToFirst()) Known(IdentityKey(c.getBlob(0)), c.getInt(1)) else null
        }

    private fun loadIdentityKey(address: String): IdentityKey? = loadIdentity(address)?.key

    /**
     * Upsert on `address`, which is UNIQUE and deliberately not partitioned by account id
     * type: a peer has one identity key, not one per local service id.
     */
    private fun insertIdentity(address: String, key: IdentityKey, trustLevel: Int) {
        db.writableDatabase.execSQL(
            """
            INSERT INTO identity (address, identity_key, added_timestamp, trust_level)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(address) DO UPDATE SET
              identity_key = excluded.identity_key,
              added_timestamp = excluded.added_timestamp,
              trust_level = excluded.trust_level
            """.trimIndent(),
            arrayOf(address, key.serialize(), System.currentTimeMillis(), trustLevel)
        )
    }

    private inline fun <T> java.util.concurrent.locks.ReentrantLock.withLockReentrant(body: () -> T): T {
        lock()
        try {
            return body()
        } finally {
            unlock()
        }
    }

    companion object {
        // Ordinals of signal-cli's TrustLevel enum, and they are stored, so they cannot drift.
        const val UNTRUSTED = 0
        const val TRUSTED_UNVERIFIED = 1
        const val TRUSTED_VERIFIED = 2
    }
}
