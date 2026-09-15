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

    /**
     * Tops up and rotates what the account holds, if either is owed -- for both identities,
     * because a PNI with no keys is a phone number nobody can open a session to.
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
     * Whether the server holds the repeated-use keys this device thinks it does.
     *
     * `POST /v2/keys/check` in one call: the identity key, the active signed prekey and the
     * last-resort Kyber key are hashed together and compared with the server's own copy. A 409
     * means they disagree -- the device and the server have diverged and only a rotation puts
     * them back. Anything else means they agree, and a rotation would achieve nothing while
     * invalidating every bundle already handed out.
     *
     * True when consistent, false when the check says otherwise **or the keys cannot be
     * loaded**. A device that cannot read its own active keys is one whose keys need
     * replacing, which is upstream's reading of the same two exceptions.
     */
    private fun keysAgreeWithServer(accountIdType: Int, serviceIdType: ServiceIdType): Boolean {
        val identity = accounts.identityKeyPair(accountIdType) ?: return true
        val signedId = accounts.activeSignedPreKeyId(accountIdType)
        val kyberId = accounts.activeLastResortKyberPreKeyId(accountIdType)
        if (signedId < 0 || kyberId < 0) return false

        val result = runCatching {
            connection.keys.checkRepeatedUseKeysSync(
                serviceIdType,
                identity.publicKey,
                signedId,
                signedPreKeys(accountIdType).loadSignedPreKey(signedId).keyPair.publicKey,
                kyberId,
                kyberPreKeys(accountIdType).loadKyberPreKey(kyberId).keyPair.publicKey
            )
        }.getOrElse {
            Timber.w(it, "signal keys: %s could not load its own keys to check them", serviceIdType)
            return false
        }
        return when {
            result is NetworkResult.Success -> true
            // Explicitly the disagreement the check exists to find.
            result is NetworkResult.StatusCodeError<*> && result.code == 409 -> false
            // Anything else -- no network, a server having a moment -- says nothing about the
            // keys. Treated as agreement so a bad minute cannot become a rotation.
            else -> {
                Timber.w("signal keys: %s could not be checked against the server: %s", serviceIdType, result)
                true
            }
        }
    }

    /**
     * Replaces the repeated-use keys because something failed to decrypt against them -- but
     * only if they are actually wrong, or it has been long enough since the last time.
     *
     * ⚠ The gate is the point. A prekey message that will not open indicts the bundle it was
     * built against, so rotating is the right instinct -- and acting on the instinct alone
     * means anyone who can send this device traffic can make it rotate both identities' entire
     * key sets, repeatedly, on the receive thread, each rotation invalidating the bundles other
     * people are holding and breaking sends that were about to work.
     *
     * Upstream asks the server first (`checkPreKeyConsistency`) and rotates only on a 409; if
     * the keys check out it falls back to `timeSinceLastForcedRotation >
     * preKeyForceRefreshInterval`, an hour, so a stream of bad envelopes costs one rotation an
     * hour rather than one each.
     *
     * @return what happened, for the log.
     */
    fun rotateIfKeysAreWrong(lastForcedAt: Long, onRotated: (Long) -> Unit): String {
        val aciAgrees = keysAgreeWithServer(ProtocolDatabase.ACCOUNT_ID_TYPE_ACI, ServiceIdType.ACI)
        val pniAgrees = accounts.identityKeyPair(ProtocolDatabase.ACCOUNT_ID_TYPE_PNI)
            ?.let { keysAgreeWithServer(ProtocolDatabase.ACCOUNT_ID_TYPE_PNI, ServiceIdType.PNI) }
            ?: true

        val since = System.currentTimeMillis() - lastForcedAt
        val reason = when {
            !aciAgrees -> "the account's keys disagree with the server"
            !pniAgrees -> "the phone-number identity's keys disagree with the server"
            // `< 0` for a clock that moved backwards, as everywhere else here.
            since > FORCE_INTERVAL_MS || since < 0 -> "they agree, but it has been long enough"
            else -> return "checked, and left alone: they agree and one was replaced recently"
        }

        Timber.w("signal keys: replacing the repeated-use keys -- %s", reason)
        val result = uploadAll()
        if (result !is Result.Failed) onRotated(System.currentTimeMillis())
        return "$reason -> $result"
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

    /**
     * The age of the oldest signed prekey this account is relying on, across both identities.
     *
     * Null when neither could be read. That is not the same as "old" and is not treated as
     * such -- see [tooOldToSendWith].
     */
    fun oldestSignedPreKeyAge(): Long? {
        val ages = listOfNotNull(
            signedPreKeyAge(ProtocolDatabase.ACCOUNT_ID_TYPE_ACI),
            signedPreKeyAge(ProtocolDatabase.ACCOUNT_ID_TYPE_PNI)
        )
        return ages.maxOrNull()
    }

    /**
     * Replaces the signed prekeys if they are too old to keep sending with.
     *
     * ⚠ **There was no such check, and [MAXIMUM_SIGNED_PREKEY_AGE_MS]'s own doc said so:**
     * *"This app has no such guard yet; the constant is here so the number has one home when
     * it gets one."* This is that home.
     *
     * The refresh runs every two days, so this should never fire -- and "should never fire" is
     * exactly the case worth guarding, because the way it fires is the refresh having failed
     * quietly for a fortnight. A phone with no usable connection for two weeks, or an upload
     * the server kept refusing, would go on signing every new session with a key that stopped
     * being fresh long ago, and nothing would have said so.
     *
     * Upstream puts the guard in the send path rather than the maintenance path, which is the
     * important part: `PushSendJob.onSend` rotates synchronously and, if that fails, refuses
     * to send (`RetryLaterException`). The maintenance pass is what *should* keep it fresh; the
     * send is the last moment anybody can be told it did not.
     *
     * @return true if it is now safe to send.
     */
    fun refreshIfTooOldToSendWith(): Boolean {
        val age = oldestSignedPreKeyAge()
        if (!tooOldToSendWith(age)) return true
        Timber.w(
            "signal keys: the signed prekey is %d day(s) old, past the limit; replacing it now",
            java.util.concurrent.TimeUnit.MILLISECONDS.toDays(age ?: 0)
        )
        val result = uploadAll()
        if (result is Result.Failed) {
            Timber.w("signal keys: could not replace the overdue signed prekey -- %s", result.reason)
            return false
        }
        return true
    }

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
     * Writes a batch down, uploads it, and only then calls it active.
     *
     * ⚠ This used to upload first, and the reason given for doing so was not true.
     *
     * The argument was that a failed store "resets the id offsets" so the next run regenerates
     * from where the server actually is. Nothing reset anything -- there is no such code in
     * the catch block or anywhere in the account store -- and the case that matters is not
     * catchable at all: if the process dies between an accepted PUT and the store loop, no
     * catch block runs. What was left behind was the bad half of the trade: the server
     * advertising a signed prekey and a last-resort Kyber key this device never wrote, with
     * `active_signed_pre_key_id` still naming the old one, and every new session failing at
     * `no signed pre key <n>` until a later refresh happened to succeed.
     *
     * Signal's order instead: persist every generated record and advance the counter first,
     * upload second, and record which key is *active* only once the upload is accepted
     * (`PreKeyUtil.generateAndStoreOneTimeEcPreKeys` stores before returning;
     * `PreKeysSyncJob` sets the active ids after `setPreKeysSync` succeeds). Then the only
     * inconsistency possible is a key this device holds that the server does not advertise --
     * which costs nothing, because nobody can ask for a key the server will not hand out.
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

        // Written first, all of it. A key held but not advertised is inert; a key advertised
        // but not held breaks every session that asks for it.
        try {
            // ⚠ The outgoing batch marks the previous one stale, which is where Signal marks
            // it (`generateAndStoreOneTimeEcPreKeys` calls
            // `markAllOneTimeEcPreKeysStaleIfNecessary` immediately before storing). Stale is
            // not deleted: a peer may already be holding one of these and about to use it, so
            // it stops being offered now and is swept long after -- see [sweepOldKeys].
            val staleFrom = System.currentTimeMillis()
            preKeys(accountIdType).markAllOneTimeEcPreKeysStaleIfNecessary(staleFrom)
            kyberPreKeys(accountIdType).markAllOneTimeKyberPreKeysStaleIfNecessary(staleFrom)

            ecKeys.forEach { preKeys(accountIdType).storePreKey(it.id, it) }
            kyberKeys.forEach { kyberPreKeys(accountIdType).storeKyberPreKey(it.id, it) }
            signedPreKeys(accountIdType).storeSignedPreKey(signedId, signed)
            kyberPreKeys(accountIdType).storeLastResortKyberPreKey(lastResortId, lastResort)
        } catch (t: Throwable) {
            Timber.w(t, "signal keys: could not write the new keys; not uploading them")
            return Result.Failed("pre keys could not be stored: ${t.message}")
        }

        val result = connection.keys.setPreKeysSync(
            PreKeyUpload(serviceIdType, signed, ecKeys, lastResort, kyberKeys)
        )
        if (result !is NetworkResult.Success) {
            // The keys are on disk and the server does not know about them, which is the
            // harmless direction: nobody can ask for a key the server will not hand out, and
            // the sweep below clears them once they are old. The active ids are deliberately
            // NOT advanced -- the old signed prekey is still the one the server advertises,
            // and it must stay the one this device calls active.
            return Result.Failed("pre key upload refused for $serviceIdType: $result")
        }

        // Only now. This is the line that says "the server is handing this one out".
        accounts.recordActiveSignedPreKey(accountIdType, signedId)
        accounts.recordActiveLastResortKyberPreKey(accountIdType, lastResortId)

        Timber.i(
            "signal keys: %s uploaded ec=%d kyber=%d signedId=%d readback=%s lastResortId=%d readback=%s",
            serviceIdType, ecKeys.size, kyberKeys.size,
            signedId, signedPreKeys(accountIdType).containsSignedPreKey(signedId),
            lastResortId, kyberPreKeys(accountIdType).containsKyberPreKey(lastResortId)
        )

        sweepOldKeys(accountIdType, serviceIdType)
        return Result.Uploaded
    }

    /**
     * Removes the keys this device no longer needs to be able to use.
     *
     * ⚠ Nothing swept anything. Every one-time key, every signed prekey and every last-resort
     * Kyber key the account had ever generated stayed on disk and stayed loadable -- two
     * hundred rows more per refresh, for ever, and the private half of every key the server
     * retired long ago still sitting there. That second part is the one that matters: the
     * point of rotating these is that a device taken later cannot open what was sent earlier,
     * and keeping them all defeats it.
     *
     * Signal sweeps at the end of every sync run, and this does it at the end of every upload
     * for the same reason: it is the moment the replacements exist.
     */
    private fun sweepOldKeys(accountIdType: Int, serviceIdType: ServiceIdType) {
        runCatching {
            // Ninety days and two hundred kept, which are Signal's numbers
            // (`PreKeyUtil.cleanOneTimePreKeys`). Long after "stale", deliberately: a peer can
            // be holding a key it fetched weeks ago.
            val threshold = System.currentTimeMillis() - ONE_TIME_KEY_LIFETIME_MS
            preKeys(accountIdType).deleteAllStaleOneTimeEcPreKeys(threshold, ONE_TIME_KEYS_KEPT)
            kyberPreKeys(accountIdType).deleteAllStaleOneTimeKyberPreKeys(threshold, ONE_TIME_KEYS_KEPT)
        }.onFailure { Timber.w(it, "signal keys: could not sweep one-time keys") }

        runCatching { cleanSuperseded(accountIdType) }
            .onFailure { Timber.w(it, "signal keys: could not sweep superseded keys") }

        Timber.i("signal keys: %s swept old keys", serviceIdType)
    }

    /**
     * Drops superseded signed prekeys and last-resort Kyber keys, keeping one generation back.
     *
     * Signal's `cleanSignedPreKeys` and `cleanLastResortKyberPreKeys`, which are the same
     * function twice: never touch the active key, consider only what is older than
     * [ARCHIVE_AGE_MS], sort those youngest first and skip one. That skip is the grace --
     * somebody may still be mid-handshake against the key this device rotated away from.
     */
    private fun cleanSuperseded(accountIdType: Int) {
        val now = System.currentTimeMillis()

        val activeSigned = accounts.activeSignedPreKeyId(accountIdType)
        if (activeSigned >= 0) {
            signedPreKeys(accountIdType).loadSignedPreKeys()
                .filter { it.id != activeSigned }
                .filter { now - it.timestamp > ARCHIVE_AGE_MS }
                .sortedByDescending { it.timestamp }
                .drop(1)
                .forEach { signedPreKeys(accountIdType).removeSignedPreKey(it.id) }
        }

        val activeLastResort = accounts.activeLastResortKyberPreKeyId(accountIdType)
        if (activeLastResort >= 0) {
            kyberPreKeys(accountIdType).loadLastResortKyberPreKeys()
                .filter { it.id != activeLastResort }
                .filter { now - it.timestamp > ARCHIVE_AGE_MS }
                .sortedByDescending { it.timestamp }
                .drop(1)
                .forEach { kyberPreKeys(accountIdType).removeKyberPreKey(it.id) }
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

        /**
         * How long a one-time key lives after it stops being offered, and how many are kept
         * regardless. Signal's `cleanOneTimePreKeys`: ninety days, two hundred.
         *
         * Long after "stale" on purpose. A peer can fetch a bundle and not use it for weeks,
         * and deleting the private half before then loses that message for good.
         */
        val ONE_TIME_KEY_LIFETIME_MS = java.util.concurrent.TimeUnit.DAYS.toMillis(90)
        const val ONE_TIME_KEYS_KEPT = 200

        /**
         * How old a superseded repeated-use key must be before it is dropped.
         *
         * Signal's `PreKeyUtil.ARCHIVE_AGE`. One generation back is always kept on top of
         * this, so the grace is "thirty days *and* not the most recent one".
         */
        val ARCHIVE_AGE_MS = java.util.concurrent.TimeUnit.DAYS.toMillis(30)

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
         * rotates synchronously and refuses to send if that fails. Ported as
         * [refreshIfTooOldToSendWith], called from the sender before anything goes out.
         */
        val MAXIMUM_SIGNED_PREKEY_AGE_MS = java.util.concurrent.TimeUnit.DAYS.toMillis(14)

        /**
         * How often a forced rotation may actually rotate when the keys check out.
         *
         * Signal's `RemoteConfig.preKeyForceRefreshInterval`, an hour. It is what stops a
         * stream of undecryptable envelopes from becoming a stream of rotations.
         */
        val FORCE_INTERVAL_MS = java.util.concurrent.TimeUnit.HOURS.toMillis(1)

        /**
         * Whether a signed prekey is too old to keep sending with.
         *
         * ⚠ **Null is not "old".** `refreshOwed` above treats an unreadable age as "refresh
         * anyway", which is right for maintenance: the cost of an unnecessary refresh is one
         * upload. Here the cost of guessing wrong is somebody's message not going, so a read
         * that could not answer is answered in the direction that costs least -- send, and let
         * the maintenance pass sort the key out. Only a definite over-age refuses.
         *
         * A negative age *is* old. That is a clock that went backwards, and upstream treats it
         * the same way (`timeSinceAciSignedPreKeyRotation < 0`): a key whose age cannot be
         * trusted is not a key to go on signing with for another fortnight.
         */
        fun tooOldToSendWith(ageMs: Long?): Boolean =
            ageMs != null && (ageMs > MAXIMUM_SIGNED_PREKEY_AGE_MS || ageMs < 0)

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
