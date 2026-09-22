package com.wanderwildwood.kotozute.signalstore

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.whispersystems.signalservice.api.SignalServiceAccountDataStore
import org.whispersystems.signalservice.api.push.DistributionId
import java.util.UUID

/**
 * The one object libsignal and the service layer are handed.
 *
 * Everything below delegates to the six stores. What lives *here* is only what the service
 * layer adds on top of libsignal's own interfaces — stale-key sweeping, session archiving, and
 * the record of which devices have already been given a sender key.
 *
 * The delegation is deliberate rather than lazy: each store owns one table and can be reasoned
 * about alone, and this class owns the operations that cross them. [archiveSession] is exactly
 * such an operation, and it is the reason the whole store takes one reentrant lock rather than
 * one per table.
 */
internal class SignalAccountDataStore(
    private val db: ProtocolDatabase,
    private val accountIdType: Int,
    private val identities: SignalIdentityKeyStore,
    private val sessions: SignalSessionStore,
    private val preKeys: SignalPreKeyStore,
    private val signedPreKeys: SignalSignedPreKeyStore,
    private val kyberPreKeys: SignalKyberPreKeyStore,
    private val senderKeys: SignalSenderKeyStore,
    /** Whether the account has any device besides this one. See [SignalDataStore]. */
    private val hasOtherDevices: () -> Boolean = { false }
) : SignalServiceAccountDataStore {

    // --- identities -----------------------------------------------------------------------

    override fun getIdentityKeyPair(): IdentityKeyPair = identities.identityKeyPair
    override fun getLocalRegistrationId(): Int = identities.localRegistrationId
    override fun saveIdentity(a: SignalProtocolAddress, k: IdentityKey) = identities.saveIdentity(a, k)
    override fun isTrustedIdentity(a: SignalProtocolAddress, k: IdentityKey, d: IdentityKeyStore.Direction) =
        identities.isTrustedIdentity(a, k, d)
    override fun getIdentity(a: SignalProtocolAddress): IdentityKey? = identities.getIdentity(a)

    // --- sessions -------------------------------------------------------------------------

    override fun loadSession(a: SignalProtocolAddress): SessionRecord = sessions.loadSession(a)
    override fun loadExistingSessions(a: List<SignalProtocolAddress>) = sessions.loadExistingSessions(a)
    override fun getSubDeviceSessions(name: String): List<Int> = sessions.getSubDeviceSessions(name)
    override fun storeSession(a: SignalProtocolAddress, r: SessionRecord) = sessions.storeSession(a, r)
    override fun containsSession(a: SignalProtocolAddress): Boolean = sessions.containsSession(a)
    override fun deleteSession(a: SignalProtocolAddress) = sessions.deleteSession(a)
    override fun deleteAllSessions(name: String) = sessions.deleteAllSessions(name)

    /** See [SignalIdentityKeyStore.adoptIdentity]. */
    fun adoptIdentity(
        address: String,
        key: org.signal.libsignal.protocol.IdentityKey,
        state: SignalIdentityKeyStore.AdoptedState
    ): Boolean = identities.adoptIdentity(address, key, state)

    /** See [SignalIdentityKeyStore.setVerified]. Passed through so the receiver need not
     *  reach past this store to the one behind it. */
    fun setVerified(
        address: String,
        verifiedKey: org.signal.libsignal.protocol.IdentityKey,
        verified: Boolean
    ): Boolean = identities.setVerified(address, verifiedKey, verified)

    /**
     * Retires a session while keeping the record, and forgets that this device was ever given
     * our sender key.
     *
     * The second half is the part that is easy to omit and expensive to omit: if the peer is
     * still listed as having the key, no fresh key is ever distributed to them, and they
     * quietly stop being able to read the group. libsignal calls this from inside a cipher
     * operation that already holds the store lock, which is precisely why that lock is
     * reentrant and shared rather than per-table.
     */
    override fun archiveSession(address: SignalProtocolAddress) = withStoreLock(db) {
        // ⚠ Only where a session actually exists. `loadSession` is libsignal's contract and
        // returns a blank record for an unknown peer -- archiving that and storing it back
        // wrote a **session row for somebody who had none**.
        //
        // Phantom rows are not inert here. `loadExistingSessions` is deliberately all-or-
        // nothing: if any address lacks a session the whole call fails, because the caller is
        // about to encrypt to a device list and a quietly shorter list means a message that
        // silently does not reach someone. A phantom row makes the counts match, so no
        // exception is raised, and libsignal is handed a record with no sender chain -- the
        // same failure by a different door.
        //
        // Upstream loads the nullable row and does nothing when it is absent
        // (`TextSecureSessionStore.archiveSession`: `if (session != null)`).
        sessions.loadSessionOrNull(address)?.let { record ->
            record.archiveCurrentState()
            sessions.storeSession(address, record)
        }

        // Outside the null check, deliberately. This says "they no longer hold our sender
        // key", which is true whether or not there was a session to archive, and it creates
        // nothing.
        clearSenderKeySharedWith(listOf(address))
    }

    override fun getAllAddressesWithActiveSessions(
        addressNames: List<String>
    ): Map<SignalProtocolAddress, SessionRecord> = withStoreLock(db) {
        addressNames
            .flatMap { name ->
                // The primary counts too; getSubDeviceSessions deliberately excludes it.
                (listOf(SignalSessionStore.PRIMARY_DEVICE_ID) + sessions.getSubDeviceSessions(name))
                    .map { SignalProtocolAddress(name, it) }
            }
            .mapNotNull { address ->
                sessions.loadSession(address).takeIf { it.hasSenderChain() }?.let { address to it }
            }
            .toMap()
    }

    // --- pre keys -------------------------------------------------------------------------

    override fun loadPreKey(id: Int): PreKeyRecord = preKeys.loadPreKey(id)
    override fun storePreKey(id: Int, r: PreKeyRecord) = preKeys.storePreKey(id, r)
    override fun containsPreKey(id: Int): Boolean = preKeys.containsPreKey(id)
    override fun removePreKey(id: Int) = preKeys.removePreKey(id)

    override fun loadSignedPreKey(id: Int): SignedPreKeyRecord = signedPreKeys.loadSignedPreKey(id)
    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = signedPreKeys.loadSignedPreKeys()
    override fun storeSignedPreKey(id: Int, r: SignedPreKeyRecord) = signedPreKeys.storeSignedPreKey(id, r)
    override fun containsSignedPreKey(id: Int): Boolean = signedPreKeys.containsSignedPreKey(id)
    override fun removeSignedPreKey(id: Int) = signedPreKeys.removeSignedPreKey(id)

    override fun loadKyberPreKey(id: Int): KyberPreKeyRecord = kyberPreKeys.loadKyberPreKey(id)
    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> = kyberPreKeys.loadKyberPreKeys()
    override fun storeKyberPreKey(id: Int, r: KyberPreKeyRecord) = kyberPreKeys.storeKyberPreKey(id, r)
    override fun containsKyberPreKey(id: Int): Boolean = kyberPreKeys.containsKyberPreKey(id)
    override fun markKyberPreKeyUsed(kyberId: Int, signedId: Int, baseKey: org.signal.libsignal.protocol.ecc.ECPublicKey) =
        kyberPreKeys.markKyberPreKeyUsed(kyberId, signedId, baseKey)

    override fun storeLastResortKyberPreKey(kyberPreKeyId: Int, kyberPreKeyRecord: KyberPreKeyRecord) =
        kyberPreKeys.storeLastResortKyberPreKey(kyberPreKeyId, kyberPreKeyRecord)

    override fun loadLastResortKyberPreKeys(): List<KyberPreKeyRecord> = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            "SELECT serialized FROM kyber_pre_key WHERE account_id_type = ? AND is_last_resort = 1",
            arrayOf(accountIdType.toString())
        ).use { c ->
            generateSequence { if (c.moveToNext()) KyberPreKeyRecord(c.getBlob(0)) else null }.toList()
        }
    }

    override fun removeKyberPreKey(kyberPreKeyId: Int) = kyberPreKeys.removeKyberPreKey(kyberPreKeyId)

    /**
     * Marks unused one-time keys stale so they can be swept later.
     *
     * Two steps rather than one on purpose, and signal-cli does the same: a key is marked when
     * it stops being offered, and only deleted well after, because a peer may already hold it
     * and be about to use it. Deleting on the first step would drop those messages.
     */
    // ⚠ Delegated, not implemented here. The sweep has to be reachable from the pre key
    // uploader, which holds the individual stores and not this facade, and a second copy of
    // the SQL is how the two drift apart.
    override fun markAllOneTimeEcPreKeysStaleIfNecessary(staleTime: Long) =
        preKeys.markAllOneTimeEcPreKeysStaleIfNecessary(staleTime)

    override fun markAllOneTimeKyberPreKeysStaleIfNecessary(staleTime: Long) =
        kyberPreKeys.markAllOneTimeKyberPreKeysStaleIfNecessary(staleTime)

    override fun deleteAllStaleOneTimeEcPreKeys(threshold: Long, minCount: Int) =
        preKeys.deleteAllStaleOneTimeEcPreKeys(threshold, minCount)

    override fun deleteAllStaleOneTimeKyberPreKeys(threshold: Long, minCount: Int) =
        kyberPreKeys.deleteAllStaleOneTimeKyberPreKeys(threshold, minCount)

    // --- sender keys ----------------------------------------------------------------------

    override fun storeSenderKey(sender: SignalProtocolAddress, id: UUID, record: SenderKeyRecord) =
        senderKeys.storeSenderKey(sender, id, record)

    override fun loadSenderKey(sender: SignalProtocolAddress, id: UUID): SenderKeyRecord? =
        senderKeys.loadSenderKey(sender, id)

    override fun getSenderKeySharedWith(distributionId: DistributionId): Set<SignalProtocolAddress> =
        withStoreLock(db) {
            // Literal, not a bound argument: rawQuery does not bind byte arrays as blobs.
            // See [toSqlBlobLiteral].
            db.readableDatabase.rawQuery(
                "SELECT address, device_id FROM sender_key_shared WHERE distribution_id = " +
                    distributionId.asUuid().toSqlBlobLiteral(),
                null
            ).use { c ->
                generateSequence {
                    if (c.moveToNext()) SignalProtocolAddress(c.getString(0), c.getInt(1)) else null
                }.toSet()
            }
        }

    override fun markSenderKeySharedWith(
        distributionId: DistributionId,
        addresses: Collection<SignalProtocolAddress>
    ) = withStoreLock(db) {
        addresses.forEach { address ->
            db.writableDatabase.execSQL(
                """
                INSERT INTO sender_key_shared (address, device_id, distribution_id, timestamp)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(address, device_id, distribution_id) DO UPDATE SET timestamp = excluded.timestamp
                """.trimIndent(),
                arrayOf<Any?>(
                    address.name, address.deviceId,
                    distributionId.asUuid().toByteArray(), System.currentTimeMillis()
                )
            )
        }
    }

    /** Across every distribution: this device must be re-sent whatever it used to hold. */
    override fun clearSenderKeySharedWith(addresses: Collection<SignalProtocolAddress>) = withStoreLock(db) {
        addresses.forEach { address ->
            db.writableDatabase.execSQL(
                "DELETE FROM sender_key_shared WHERE address = ? AND device_id = ?",
                arrayOf<Any?>(address.name, address.deviceId)
            )
        }
    }

    // --- account --------------------------------------------------------------------------

    /**
     * Whether the account has any device besides this one.
     *
     * ⛔ Was the literal `true`. See [SignalDataStore.isMultiDevice] for what that cost once a
     * phone could register an account of its own and genuinely be alone on it.
     *
     * The setter exists for the interface and has nothing to record: the answer is derived,
     * so there is no stored copy of it to get out of step.
     */
    override fun isMultiDevice(): Boolean = hasOtherDevices()
    override fun setMultiDevice(isMultiDevice: Boolean) = Unit
}
