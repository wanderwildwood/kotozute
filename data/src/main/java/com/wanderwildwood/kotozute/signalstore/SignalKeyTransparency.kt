package com.wanderwildwood.kotozute.signalstore

import org.signal.libsignal.net.KeyTransparency
import org.signal.libsignal.net.RequestResult
import timber.log.Timber
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Asks Signal's public log whether this account's own identifiers are what the server says.
 *
 * Key transparency is the answer to a question the rest of the protocol cannot ask: **is the
 * server telling everybody the truth about me?** Sealed sender, safety numbers and the session
 * ratchet all assume the identity key handed out for an account is that account's. A malicious
 * or compromised server could hand out a different one. The log is append-only and publicly
 * verifiable, so a substitution has to be published to be used — and this check is how a device
 * notices it was.
 *
 * Ported from `CheckKeyTransparencyJob`, including the parts that look like hesitancy and are
 * not:
 *
 * - **A first failure is not shown to anybody.** It is far more likely to be stale local data
 *   than a lying server, so Signal re-reads what the account says, re-sends what this device
 *   says, and checks again a day later. Only a *second* failure reaches a person.
 * - **The next check time is written before the check runs**, not after it succeeds. A check
 *   that recorded its next time only on success would retry on every pass after a crash, making
 *   a failed verification into a hot loop against the server.
 * - **Seven days, plus a random 0-8 hours.** The jitter is why two phones in one house do not
 *   ask in the same second.
 *
 * ⚠ **What this does not do is decide anything about the answer.** libsignal verifies the log's
 * own proofs; this code supplies the identifiers, keeps two opaque blobs between calls
 * ([SignalKeyTransparencyStore]) and reports. A client that formed its own view of the evidence
 * would be a client that can be argued out of it.
 */
internal class SignalKeyTransparency(
    private val accounts: SignalAccountStore,
    private val contacts: SignalContactStore,
    private val connection: SignalConnection
) {

    /** Why a check did not happen, or that it did and what came of it. */
    sealed interface Outcome {
        /** Not due yet, or this account cannot be checked. Says which, for the log. */
        data class Skipped(val because: String) : Outcome

        /** The log agrees with the server. */
        data object Verified : Outcome

        /**
         * The log and the server disagree. [tellSomebody] only on the second failure running.
         */
        data class Failed(val reason: String, val tellSomebody: Boolean) : Outcome

        /** The network, not the answer. Never counted as a failure. */
        data class Unreachable(val reason: String) : Outcome
    }

    /**
     * Runs a check if one is due.
     *
     * @param now the clock, injected so the schedule can be tested without waiting a week.
     * @param nextDueAt what this device last recorded as the next check time.
     * @param alreadyFailing whether the previous check failed; the second one tells somebody.
     * @param setNextDueAt records the next check time. Called **before** the check.
     */
    suspend fun checkIfDue(
        now: Long,
        nextDueAt: Long,
        alreadyFailing: Boolean,
        setNextDueAt: (Long) -> Unit
    ): Outcome {
        val credentials = runCatching { accounts.credentials() }.getOrNull()
            ?: return Outcome.Skipped("the account cannot be read")
        if (!credentials.complete) return Outcome.Skipped("this device is not linked")

        val aci = credentials.aci?.let { org.signal.core.models.ServiceId.ACI.parseOrNull(it) }
            ?: return Outcome.Skipped("no account id")

        // ⚠ Signal skips an account with no phone number outright -- `canRunJob` ends at
        // `!Recipient.self().hasE164`. The check proves a *binding* between an ACI and an E164,
        // and there is nothing to prove without one. An account registered without a number is
        // not a broken case here; it is out of scope for the mechanism.
        val e164 = credentials.e164?.takeIf { it.isNotBlank() }
            ?: return Outcome.Skipped("this account has no phone number, which is what the check binds")

        if (now < nextDueAt) return Outcome.Skipped("not due until $nextDueAt")

        // Before the check, never after it. See the class note.
        setNextDueAt(now + INTERVAL_MS + Random.nextLong(0, JITTER_MS))

        val identityKey = runCatching {
            accounts.identityKeyPair(ProtocolDatabase.ACCOUNT_ID_TYPE_ACI)?.publicKey
        }.getOrNull() ?: return Outcome.Skipped("no identity key for this account")

        // The access key is derived from our own profile key, the same way a sealed-sender
        // access key is. Absent, the check simply carries one less identifier.
        val accessKey = runCatching { accounts.profileKey() }.getOrNull()
            ?.let { key ->
                runCatching {
                    org.whispersystems.signalservice.api.crypto.UnidentifiedAccess
                        .deriveAccessKeyFrom(org.signal.libsignal.zkgroup.profiles.ProfileKey(key))
                }.getOrNull()
            }

        val result = runCatching {
            connection.unauthenticated.runCatchingWithChatConnection { chat ->
                chat.keyTransparencyClient().check(
                    KeyTransparency.CheckMode.Self(DISCOVERABLE_BY_NUMBER),
                    aci.libSignalAci,
                    identityKey,
                    e164,
                    accessKey,
                    // No username on this account, and Signal omits the hash when there is
                    // none rather than sending an empty one.
                    null,
                    SignalKeyTransparencyStore(accounts, contacts)
                )
            }
        }.getOrElse { return Outcome.Unreachable(it.message ?: it::class.java.simpleName) }

        return when (result) {
            is RequestResult.Success -> Outcome.Verified
            is RequestResult.NonSuccess -> Outcome.Failed(
                reason = result.toString().take(120),
                // ⚠ The first failure is not shown. Signal's reasoning, and it is sound: a
                // mismatch is far more often this device holding something stale than the
                // server lying, and telling somebody their account may be compromised is not
                // a thing to do on one reading.
                tellSomebody = alreadyFailing
            )
            else -> Outcome.Unreachable(result.toString().take(120))
        }
    }

    companion object {
        /** `CheckKeyTransparencyJob.TIME_BETWEEN_CHECK`. */
        private val INTERVAL_MS = TimeUnit.DAYS.toMillis(7)

        /** `getRandomDelay(maxHours = 8)`, so two phones in a house do not ask together. */
        private val JITTER_MS = TimeUnit.HOURS.toMillis(8)

        /**
         * ⚠ This app does not expose phone-number discoverability, and Signal's default for an
         * account is discoverable. Saying `false` when the account is in fact discoverable asks
         * the log to prove the wrong thing and fails a check that should pass.
         */
        private const val DISCOVERABLE_BY_NUMBER = true
    }
}
