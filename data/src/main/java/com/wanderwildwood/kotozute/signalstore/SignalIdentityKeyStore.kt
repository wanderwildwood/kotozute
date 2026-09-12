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
                // ⚠ And their other devices' sessions go with it. Recording the new key while
                // leaving those sessions in place means the next send to one of them is
                // encrypted against an identity this device has just decided it does not
                // trust -- and arrives as a message they cannot read, on one device only,
                // which reads as "some of your messages don't get through" rather than as
                // anything to do with a safety number.
                //
                // `SignalBaseIdentityKeyStore.saveIdentity` does exactly this on a replaced
                // key: archiveSiblingSessions, and forget any sender key shared with them.
                archiveSiblingSessions(address)
                Timber.i("signal store: identity changed for a peer; recorded untrusted and sessions archived")
                IdentityKeyStore.IdentityChange.REPLACED_EXISTING
            }
        }
    }

    /**
     * Signal's policy: guard what we send, never refuse what we are sent.
     *
     * The asymmetry is the point, and it runs the opposite way to intuition. **Sending** to a
     * changed key is the dangerous direction -- that is where a message could go to somebody
     * who is not who the user thinks -- so a changed key is recorded untrusted and the send
     * stops at this check. **Receiving** is unconditionally trusted, because refusing protects
     * nothing: the message was encrypted to us, reading it tells us who it is really from, and
     * the safety number shown afterwards is what lets the user judge it.
     *
     * ⚠ This used to return false on a changed key when receiving, described as signal-cli's
     * policy. It is signal-cli's, but signal-cli is a shell where an operator then runs
     * `trust`; there is no such step here. The effect was that a contact who reinstalled
     * Signal or got a new phone became **permanently unreachable**: their next message throws
     * an untrusted-identity error, the retained envelope is retried and fails identically for
     * ever, and nothing in the app could accept the new key, because accepting only ever
     * re-accepted the key already stored. A conversation that could never recover.
     *
     * See `SignalBaseIdentityKeyStore.isTrustedIdentity` in Signal Android, which is two lines
     * for exactly this reason.
     */
    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction
    ): Boolean = db.lock.withLockReentrant {
        // ⚠ Ourselves first, and never waved through. Signal's own store checks this before
        // anything else: a key claiming to be this account must be **this account's key**.
        // Reading what somebody else sent is always allowed, and that rule applied to our own
        // address would accept a message encrypted to an identity that is not ours -- which is
        // what somebody impersonating the account's own devices would send. The rest of the
        // receive path already refuses a sync message from anyone but us; this refuses the
        // key underneath it.
        val credentials = SignalAccountStore(db).credentials()
        val isSelf = listOfNotNull(credentials.aci, credentials.pni, credentials.e164)
            .any { it.isNotBlank() && it == address.name }
        if (isSelf) {
            return@withLockReentrant runCatching {
                identityKeyPair.publicKey == identityKey
            }.getOrDefault(false)
        }

        // Reading what somebody sent us is always allowed. libsignal calls saveIdentity next,
        // which records the new key and marks it untrusted, so the *next send* still stops
        // here and the user still gets told the safety number changed.
        if (direction == IdentityKeyStore.Direction.RECEIVING) return@withLockReentrant true

        val name = address.name
        var known = loadIdentity(name)
        if (known == null) {
            insertIdentity(name, identityKey, TRUSTED_UNVERIFIED)
            known = loadIdentity(name)
        } else if (known.key != identityKey) {
            insertIdentity(name, identityKey, UNTRUSTED)
            known = loadIdentity(name)
        }
        known != null && known.trustLevel > UNTRUSTED
    }

    /**
     * Archives the sessions with this person's **other** devices.
     *
     * The session with the address itself is left to libsignal, which starts a fresh one
     * against the new identity. The siblings are the ones nothing else would touch.
     */
    private fun archiveSiblingSessions(address: SignalProtocolAddress) {
        val sessions = SignalSessionStore(db, accountIdType)
        runCatching {
            sessions.getSubDeviceSessions(address.name)
                .filter { it != address.deviceId }
                .forEach { deviceId ->
                    val sibling = SignalProtocolAddress(address.name, deviceId)
                    val record = sessions.loadSession(sibling)
                    record.archiveCurrentState()
                    sessions.storeSession(sibling, record)
                }
        }.onFailure { Timber.w(it, "signal store: could not archive the sibling sessions") }
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

    /**
     * Records what the account has decided about somebody's safety number elsewhere.
     *
     * Verifying is a thing a person does once, in the room, by comparing numbers. That
     * decision belongs to the account rather than to the device it was made on, so a linked
     * device that ignores this shows every one of those people as unverified and quietly
     * throws away work somebody did carefully.
     *
     * ⚠ **Only when the key matches.** A verification is a statement about one specific key;
     * applied to a different one it would mark as verified a key nobody has ever checked,
     * which is worse than showing nothing. A mismatch means this device has since seen a
     * change the verifying device had not, and the right answer is to leave it alone.
     *
     * @return false where there is no key here or it is not the key that was verified.
     */
    fun setVerified(address: String, verifiedKey: IdentityKey, verified: Boolean): Boolean =
        db.lock.withLockReentrant {
            val known = loadIdentity(address) ?: return@withLockReentrant false
            if (known.key != verifiedKey) {
                Timber.w("signal identity: a verification named a key this phone does not hold")
                return@withLockReentrant false
            }
            // Not UNTRUSTED for the un-verified case. Signal has three states and this store
            // has three levels, but they are not the same three: "explicitly not verified" is
            // a person you still talk to, where UNTRUSTED here stops messages going out. The
            // honest mapping is the one that does not invent a block nobody asked for.
            insertIdentity(address, known.key, if (verified) TRUSTED_VERIFIED else TRUSTED_UNVERIFIED)
            true
        }

    /**
     * Takes the key the account itself already holds for somebody as this device's own.
     *
     * Written only where nothing is on file. A key already here came from a real session or a
     * real verification, and a storage record -- which can be older than both -- must not
     * quietly replace it; that is the same overwrite the receive path deliberately refuses.
     *
     * What it is for is the gap before any of that: on a freshly linked device every contact
     * is a first sighting, and a first sighting is trusted on faith. Starting from the
     * account's own record closes that window for everybody it knows about.
     */
    fun adoptIdentity(address: String, key: IdentityKey, verified: Boolean): Boolean =
        db.lock.withLockReentrant {
            if (loadIdentity(address) != null) return@withLockReentrant false
            insertIdentity(address, key, if (verified) TRUSTED_VERIFIED else TRUSTED_UNVERIFIED)
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
