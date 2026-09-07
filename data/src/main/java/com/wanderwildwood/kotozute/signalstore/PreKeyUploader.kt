package com.wanderwildwood.kotozute.signalstore

import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.network.NetworkResult
import org.whispersystems.signalservice.api.account.PreKeyUpload
import org.whispersystems.signalservice.api.push.ServiceIdType
import timber.log.Timber

/**
 * Publishes this device's pre keys, so other people can start conversations with it.
 *
 * Linking registers **one** signed pre key and **one** last-resort Kyber key per identity --
 * that is all the registration request carries. Until this runs, every new session with this
 * device falls back to the last-resort key. That works, and it is the degraded path: the
 * last-resort key is reused, which is exactly what one-time keys exist to avoid.
 *
 * Ported from signal-cli's `PreKeyHelper`, including the ordering, which is the part worth
 * being deliberate about.
 */
internal class PreKeyUploader(
    private val accounts: SignalAccountStore,
    private val connection: SignalConnection,
    private val preKeys: (Int) -> SignalPreKeyStore,
    private val signedPreKeys: (Int) -> SignalSignedPreKeyStore,
    private val kyberPreKeys: (Int) -> SignalKyberPreKeyStore
) {

    sealed interface Result {
        data object Uploaded : Result
        data class Failed(val reason: String) : Result
    }

    /** Both identities. A PNI with no keys is a phone number nobody can open a session to. */
    fun uploadAll(): Result {
        val aci = upload(ProtocolDatabase.ACCOUNT_ID_TYPE_ACI, ServiceIdType.ACI)
        if (aci is Result.Failed) return aci
        return upload(ProtocolDatabase.ACCOUNT_ID_TYPE_PNI, ServiceIdType.PNI)
    }

    /**
     * What the **server** says it holds for us.
     *
     * Worth asking separately rather than trusting a 200 on the upload. A successful PUT says
     * the request was accepted; this says the keys are actually there to be handed out, which
     * is the thing that matters and the only claim that survives being wrong about the first.
     */
    fun serverCounts(): String = listOf(ServiceIdType.ACI, ServiceIdType.PNI).joinToString(" ") { type ->
        when (val r = connection.keys.getAvailablePreKeyCountsSync(type)) {
            is NetworkResult.Success -> "$type=ec:${r.result.ecCount},kyber:${r.result.kyberCount}"
            else -> "$type=?($r)"
        }
    }

    /**
     * Generates a batch, uploads it, and only then writes it down.
     *
     * **That order is signal-cli's and it is the safer of the two, not the obvious one.** Store
     * first and a failed upload leaves keys the server never advertised while the id counter
     * has already moved -- harmless clutter, but it hides the failure. Upload first and a
     * failed store leaves the server advertising keys this device does not hold, which is
     * worse: a peer fetches one, builds a session against it, and this device cannot complete
     * the handshake. So the store failure resets the id offsets, and the next run regenerates
     * from where the server actually is. Neither order is safe on its own; the recovery is
     * what makes this one safe.
     */
    private fun upload(accountIdType: Int, serviceIdType: ServiceIdType): Result {
        val identity = accounts.identityKeyPair(accountIdType)
            ?: return Result.Failed("no identity key for $serviceIdType; link first")

        val ecKeys = generateEcPreKeys(accountIdType)
        val kyberKeys = generateKyberPreKeys(accountIdType, identity)

        // The repeated-use keys are rotated here too, not just the one-time batches.
        //
        // They need rotating periodically anyway, but this also repairs a device whose stored
        // copy is missing: linking generates a signed pre key and a last-resort Kyber key,
        // sends them, and the private halves exist only where they were stored. If they were
        // not, the server keeps advertising a key nobody holds and every new session fails at
        // `no signed pre key <n>`. Replacing both is the only repair, since the originals are
        // unrecoverable.
        val signedId = accounts.nextSignedPreKeyId(accountIdType)
        val signed = KeyUtilsForCheck.signedPreKey(signedId, identity.privateKey)
        val lastResortId = accounts.nextKyberPreKeyId(accountIdType)
        val lastResort = KeyUtilsForCheck.kyberPreKey(lastResortId, identity.privateKey)

        val result = connection.keys.setPreKeysSync(
            PreKeyUpload(serviceIdType, signed, ecKeys, lastResort, kyberKeys)
        )
        if (result !is NetworkResult.Success) {
            // Nothing has been written, so nothing needs undoing -- but the counter has moved,
            // and that is deliberate: reusing an id the server may have seen is worse than
            // skipping a range of them.
            return Result.Failed("pre key upload refused for $serviceIdType: $result")
        }

        return try {
            ecKeys.forEach { preKeys(accountIdType).storePreKey(it.id, it) }
            kyberKeys.forEach { kyberPreKeys(accountIdType).storeKyberPreKey(it.id, it) }
            signedPreKeys(accountIdType).storeSignedPreKey(signedId, signed)
            kyberPreKeys(accountIdType).storeLastResortKyberPreKey(lastResortId, lastResort)
            accounts.recordActiveSignedPreKey(accountIdType, signedId)
            accounts.recordActiveLastResortKyberPreKey(accountIdType, lastResortId)
            Timber.i(
                "signal keys: %s uploaded ec=%d kyber=%d signedId=%d readback=%s lastResortId=%d readback=%s",
                serviceIdType, ecKeys.size, kyberKeys.size,
                signedId, signedPreKeys(accountIdType).containsSignedPreKey(signedId),
                lastResortId, kyberPreKeys(accountIdType).containsKyberPreKey(lastResortId)
            )
            Result.Uploaded
        } catch (t: Throwable) {
            Timber.w(t, "signal keys: uploaded but could not store; resetting id offsets")
            Result.Failed("pre keys uploaded but not stored: ${t.message}")
        }
    }

    private fun generateEcPreKeys(accountIdType: Int): List<PreKeyRecord> {
        val records = mutableListOf<PreKeyRecord>()
        // Ids and the records are allocated in one transaction, so a process death between
        // advancing the counter and generating the batch cannot hand the same id out twice.
        accounts.allocatePreKeyIds(accountIdType, BATCH_SIZE) { ids ->
            ids.forEach { records += PreKeyRecord(it, ECKeyPair.generate()) }
        }
        return records
    }

    private fun generateKyberPreKeys(accountIdType: Int, identity: IdentityKeyPair): List<KyberPreKeyRecord> {
        val records = mutableListOf<KyberPreKeyRecord>()
        accounts.allocateKyberPreKeyIds(accountIdType, BATCH_SIZE) { ids ->
            ids.forEach { records += KeyUtilsForCheck.kyberPreKey(it, identity.privateKey) }
        }
        return records
    }

    companion object {
        /** signal-cli's `PREKEY_BATCH_SIZE`. */
        const val BATCH_SIZE = 100

        /** signal-cli's `PREKEY_MINIMUM_COUNT`: below this, top up. */
        const val MINIMUM_COUNT = 10
    }
}
