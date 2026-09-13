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
        /** Nothing was owed: enough keys on the server and the repeated-use ones still young. */
        data object NotNeeded : Result
        data class Failed(val reason: String) : Result
    }

    /** Both identities. A PNI with no keys is a phone number nobody can open a session to. */
    /**
     * Tops up and rotates what the account holds, if either is owed.
     *
     * ⚠ **Without this the device degrades in silence.** One batch of one-time keys is
     * published at link time and never replenished, and the server hands each one out once.
     * After about a hundred new sessions -- which is per peer *device*, so it is front-loaded
     * in the days after linking rather than years away -- the server has none left and gives
     * every later requester a bundle with no one-time key and the same last-resort Kyber key.
     * Sessions still establish; they establish without the initial-message forward secrecy
     * those keys exist to provide, for ever, and nothing about the phone looks wrong.
     *
     * The repeated-use keys have the same problem from the other end: generated once at
     * linking and never rotated, so one signed prekey signs for the life of the install.
     *
     * `upload` already replaces all of it in one request, so the work here is deciding *when*,
     * which is the part that did not exist. That decision is now Signal's: every
     * [REFRESH_INTERVAL_MS], the same gate `PreKeysSyncJob.checkPreKeys` puts in front of its
     * whole job, and nothing is asked of the server in between.
     */
    fun maintain(): Result {
        val aci = maintainOne(ProtocolDatabase.ACCOUNT_ID_TYPE_ACI, ServiceIdType.ACI)
        if (aci is Result.Failed) return aci
        val pni = maintainOne(ProtocolDatabase.ACCOUNT_ID_TYPE_PNI, ServiceIdType.PNI)
        return if (pni is Result.Failed) pni else if (aci is Result.Uploaded) aci else pni
    }

    private fun maintainOne(accountIdType: Int, serviceIdType: ServiceIdType): Result {
        // An account with no identity of this kind has nothing to maintain. Not a failure:
        // a linked device without a PNI is an ordinary state, not a broken one.
        if (accounts.identityKeyPair(accountIdType) == null) return Result.NotNeeded

        // Nothing is asked of the server until the interval is up. Signal gates the whole
        // thing the same way: `PreKeysSyncJob.checkPreKeys` enqueues the job only when a key
        // is unregistered or inactive, or `timeSinceLastFullRefresh >= REFRESH_INTERVAL`, and
        // the counts are read *inside* the job. The trade is Signal's too -- one-time keys
        // running out is noticed on the next interval rather than within the quarter hour,
        // which is what a hundred keys and two days are sized for.
        val age = signedPreKeyAge(accountIdType)
        if (!refreshOwed(age)) {
            // Said out loud, because otherwise "nothing was owed" and "this never ran" look
            // identical in the log -- and the gate above is the whole change. Signal logs the
            // same branch: "No prekey job needed. Time since last full refresh: ...".
            Timber.i(
                "signal keys: %s not due for %d more hour(s)",
                serviceIdType,
                java.util.concurrent.TimeUnit.MILLISECONDS.toHours(REFRESH_INTERVAL_MS - (age ?: 0))
            )
            return Result.NotNeeded
        }

        val counts = countsFor(serviceIdType)
        // Read before the refill, so it says what the interval cost rather than what it fixed.
        // Below the minimum here means the one-time keys ran out *before* the interval came
        // round, and sessions started in the gap got a bundle with no one-time key. If this
        // ever appears, the answer is a trigger, not a shorter interval -- Signal enqueues its
        // job on demand as well as on the clock.
        if (counts != null && (counts.first < MINIMUM_COUNT || counts.second < MINIMUM_COUNT)) {
            Timber.w(
                "signal keys: %s ran low before its refresh was due (ec=%d kyber=%d)",
                serviceIdType, counts.first, counts.second
            )
        }
        Timber.i(
            "signal keys: %s refreshing (server ec=%s kyber=%s)",
            serviceIdType, counts?.first ?: "?", counts?.second ?: "?"
        )
        return upload(accountIdType, serviceIdType)
    }

    /**
     * Replaces one identity's repeated-use keys now, whatever the clock says.
     *
     * For the one case Signal forces a rotation outside its own schedule: a change of the
     * account's phone number. The primary generates that new PNI signed prekey and last-resort
     * Kyber key and hands them to every linked device inside a sync message, and Signal's
     * comment at the point it does so says why this has to follow --
     * *"Rotate the primary-generated keys as soon as possible so we don't rely on them
     * long-term."* It sets `forcePniSignedPreKeyRotation` and enqueues
     * `PreKeysSyncJob.create(forceRotationRequested = true)`, which bypasses the interval.
     *
     * ⚠ The interval cannot catch this on its own here. [signedPreKeyAge] reads the stored
     * record's own timestamp, and the record the primary just sent is brand new -- so storing
     * it sets this device's rotation clock back to zero and the primary's key stays in force
     * for the whole two days.
     */
    fun rotateNow(serviceIdType: ServiceIdType): Result = upload(
        when (serviceIdType) {
            ServiceIdType.PNI -> ProtocolDatabase.ACCOUNT_ID_TYPE_PNI
            else -> ProtocolDatabase.ACCOUNT_ID_TYPE_ACI
        },
        serviceIdType
    )

    /**
     * Puts one-time keys back when the server is running low, whatever the clock says.
     *
     * The other half of [maintain]. That one is the periodic path and is gated on
     * [REFRESH_INTERVAL_MS]; this is the on-demand one, and Signal has both: `MessageDecryptor`
     * schedules a `PreKeysSyncJob` the moment a PREKEY_MESSAGE envelope arrives, and the job
     * reads the server's counts and refills below `ONE_TIME_PREKEY_MINIMUM` without waiting
     * for anything. A prekey message *is* the server handing out one of these keys, so it is
     * the one event that says the pile is shrinking.
     *
     * ⚠ One-time keys only. The signed prekey and the last-resort Kyber key are left alone --
     * `PreKeyUpload` takes each part as null for exactly this, and rotating the repeated-use
     * keys on somebody else's schedule is not what upstream does here.
     */
    fun refillOneTimeIfShort(): Result {
        val aci = refillOne(ProtocolDatabase.ACCOUNT_ID_TYPE_ACI, ServiceIdType.ACI)
        if (aci is Result.Failed) return aci
        val pni = refillOne(ProtocolDatabase.ACCOUNT_ID_TYPE_PNI, ServiceIdType.PNI)
        return if (pni is Result.Failed) pni else if (aci is Result.Uploaded) aci else pni
    }

    private fun refillOne(accountIdType: Int, serviceIdType: ServiceIdType): Result {
        val identity = accounts.identityKeyPair(accountIdType) ?: return Result.NotNeeded
        // Null means the server would not say, which is not a reason to send it a hundred
        // keys. The periodic path will come round.
        val counts = countsFor(serviceIdType) ?: return Result.NotNeeded
        if (counts.first >= MINIMUM_COUNT && counts.second >= MINIMUM_COUNT) return Result.NotNeeded

        val ecKeys = generateEcPreKeys(accountIdType)
        val kyberKeys = generateKyberPreKeys(accountIdType, identity)
        val result = connection.keys.setPreKeysSync(
            PreKeyUpload(serviceIdType, null, ecKeys, null, kyberKeys)
        )
        if (result !is NetworkResult.Success) {
            return Result.Failed("one-time pre key refill refused for $serviceIdType: $result")
        }
        return try {
            ecKeys.forEach { preKeys(accountIdType).storePreKey(it.id, it) }
            kyberKeys.forEach { kyberPreKeys(accountIdType).storeKyberPreKey(it.id, it) }
            Timber.i(
                "signal keys: %s refilled one-time keys (server was ec=%d kyber=%d, sent %d+%d)",
                serviceIdType, counts.first, counts.second, ecKeys.size, kyberKeys.size
            )
            Result.Uploaded
        } catch (t: Throwable) {
            Timber.w(t, "signal keys: refilled but could not store")
            Result.Failed("one-time pre keys uploaded but not stored: ${t.message}")
        }
    }

    /** What the server holds, as numbers rather than a log line. Null when it will not say. */
    private fun countsFor(serviceIdType: ServiceIdType): Pair<Int, Int>? =
        when (val r = connection.keys.getAvailablePreKeyCountsSync(serviceIdType)) {
            is NetworkResult.Success -> r.result.ecCount to r.result.kyberCount
            else -> null
        }

    /**
     * How long the signed prekey in force has been in force, or null if that cannot be read.
     *
     * Null and negative both mean "rotate". A device that cannot read its own active key is
     * exactly the one that should replace it, and a negative age is a clock that has moved
     * backwards -- which would otherwise put the key permanently in the future and stop
     * rotation coming round ever again. Signal tests `< 0` beside the threshold in both
     * `PreKeysSyncJob` and the send path for the same reason, and these machines keep their
     * RTC in local time, so it is not theoretical.
     */
    private fun signedPreKeyAge(accountIdType: Int): Long? = runCatching {
        val active = accounts.activeSignedPreKeyId(accountIdType)
        if (active < 0) return@runCatching null
        System.currentTimeMillis() - signedPreKeys(accountIdType).loadSignedPreKey(active).timestamp
    }.getOrNull()

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

        /** signal-cli's `PREKEY_MINIMUM_COUNT`, and Signal's `ONE_TIME_PREKEY_MINIMUM`. */
        const val MINIMUM_COUNT = 10

        /**
         * How often the repeated-use keys are replaced.
         *
         * Signal's `PreKeysSyncJob.REFRESH_INTERVAL`, whose comment is exactly this: "How
         * often we want to rotate signed prekeys and last-resort kyber prekeys."
         *
         * ⚠ This was fourteen days, taken from [MAXIMUM_SIGNED_PREKEY_AGE_MS] below and
         * described in a comment as "Signal's own ceiling" -- which was true, and was the
         * wrong number. A ceiling is not a cadence: Signal rotates seven times inside it and
         * treats reaching it as a fault to be repaired before a message can go out. Sitting
         * exactly on the ceiling meant one signed prekey signed for every session this device
         * accepted for a fortnight at a time, which is the thing rotation exists to stop.
         */
        val REFRESH_INTERVAL_MS = java.util.concurrent.TimeUnit.DAYS.toMillis(2)

        /**
         * The age past which a signed prekey is a fault rather than merely old.
         *
         * Signal's `MAXIMUM_ALLOWED_SIGNED_PREKEY_AGE`: "If signed prekeys or last-resort
         * kyber keys are older than this, we will require rotation before sending messages."
         * It is a stop in the *send* path (`PushSendJob`, `IndividualSendJobV2`), which
         * rotates synchronously and refuses to send if that fails. This app has no such guard
         * yet; the constant is here so the number has one home when it gets one.
         */
        val MAXIMUM_SIGNED_PREKEY_AGE_MS = java.util.concurrent.TimeUnit.DAYS.toMillis(14)

        /**
         * Whether the repeated-use keys are owed a refresh, given the age of the signed prekey
         * in force. Null is an age that could not be read.
         *
         * Pure, and separate from the stores, so the three cases that matter can be tested
         * without a device or a network: never rotated, rotated recently, and a clock that has
         * moved backwards. The last one is the reason this is not simply `age >= interval`.
         */
        fun refreshOwed(ageMs: Long?): Boolean =
            ageMs == null || ageMs >= REFRESH_INTERVAL_MS || ageMs < 0
    }
}
