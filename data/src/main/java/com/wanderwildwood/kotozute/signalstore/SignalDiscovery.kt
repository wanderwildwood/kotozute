package com.wanderwildwood.kotozute.signalstore

import org.signal.network.api.CdsApi
import timber.log.Timber
import java.util.Optional
import java.util.concurrent.TimeUnit

/**
 * Turning phone numbers into Signal accounts.
 *
 * [SignalStorageService] reads the people the account already has a record for. This asks the
 * other question -- *is this number on Signal at all* -- which is the one a linked device
 * otherwise cannot answer. Without it, somebody sitting in the phone's own address book, on
 * Signal and reachable, could never be written to from here: nothing but an account id
 * identifies a person to Signal, and this app had no way to learn one.
 *
 * The lookup runs inside libsignal's enclave client. Numbers go up hashed and the service is
 * built so it cannot read them -- that is what CDSI is for, and it is the same mechanism
 * Signal itself uses. It is still the first thing in this app that sends the address book
 * anywhere, so it runs when somebody asks for it and never on its own.
 *
 * ⚠ **Quota.** The service charges an account for numbers it has not been asked about before,
 * and refuses further lookups for a long while once that is spent. The token and the
 * already-asked set in [SignalDiscoveryStore] are what keep a second run cheap. They are not
 * an optimisation, and losing them is how an account loses discovery for a day.
 */
internal class SignalDiscovery(
    private val connection: SignalConnection,
    private val contacts: SignalContactStore,
    private val state: SignalDiscoveryStore
) {

    /**
     * @param found people whose number is on Signal and whose account id came back.
     * @param withoutAci numbers on Signal whose owner does not publish an account id --
     *   discoverable as a phone-number identity and no more.
     * @param asked how many numbers this run submitted, which is what it was charged for.
     *   Zero means everything had been asked before and nothing was spent.
     */
    data class Result(
        val found: Int,
        val withoutAci: Int,
        val asked: Int,
        val reason: String? = null
    )

    /**
     * Asks about [numbers], minus whatever has been asked before.
     *
     * Numbers must already be E.164. The service takes nothing else, and one it cannot parse
     * is quota spent for no answer.
     */
    fun read(numbers: Set<String>): Result {
        // ⚠ Signal's own `sanitize`, ported. This was "starts with + and longer than three",
        // which admits a non-numeric string and a leading-zero number -- and the service
        // rejects the **whole batch** as an invalid argument for one bad entry, so a single
        // malformed address-book row meant nobody in that run was discovered.
        val valid = numbers.filterTo(mutableSetOf()) { candidate ->
            try {
                candidate.startsWith("+") &&
                    candidate.length > 1 &&
                    candidate[1] != '0' &&
                    candidate.toLong() > 0
            } catch (e: NumberFormatException) {
                false
            }
        }
        if (valid.isEmpty()) return Result(0, 0, 0, "There are no numbers to look up")

        // Nothing to ask while the service has said it will not answer. The refusal carried
        // the only number that says how long; see [SignalDiscoveryStore.blockUntil].
        val blockedFor = runCatching { state.blockedFor() }.getOrDefault(0L)
        if (blockedFor > 0) {
            val minutes = (blockedFor / 60_000L) + 1
            return Result(0, 0, 0, "Signal will not answer more lookups for about $minutes more minute(s)")
        }

        // ⚠ Read as empty, this asks about every number as though it had never asked. The
        // numbers asked about before are exactly the ones the token makes free, so a failure
        // here is not a slower lookup -- it is the account's quota spent a second time on
        // answers it has already paid for. It is still better than refusing a lookup somebody
        // deliberately asked for, so it goes ahead and says what it could not read.
        val previous = runCatching { state.submitted() }
            .onFailure {
                Timber.w(
                    it,
                    "signal discovery: could not read which numbers have been asked about " +
                        "before; asking about all of them, which spends quota again"
                )
            }
            .getOrDefault(emptySet())
        val fresh = valid - previous
        // ⚠ Only when there is nothing at all to send. This used to stop as soon as every
        // number had been asked about before, on the reasoning that the answers were already
        // in the contact store -- but the numbers asked about before are exactly the ones the
        // token makes **free**, and the answer to them changes: somebody who was not on Signal
        // at the first lookup joins, and was being told "no" permanently, because nothing ever
        // asked again. Signal returns early only when the new and the previous sets are both
        // empty, and otherwise resubmits the lot with the token.
        if (fresh.isEmpty() && previous.isEmpty()) return Result(0, 0, 0, null)

        connection.connect()
        val token = runCatching { state.token() }.getOrNull()

        // The previous set is only meaningful with the token that covers it: the token is what
        // lets the service discount those numbers, and the two are one pair. Sent without it,
        // the service either counts them all as new -- quota spent on answers already held --
        // or refuses the request outright as an invalid token. Without a token this is a first
        // run, and says so.
        val previouslyAsked = if (token != null) previous else emptySet()

        // ⚠ And the other way round, which was missing. Signal drops the **token** when the
        // previous set is empty just as it drops the set when there is no token
        // (`ContactDiscoveryRefreshV2`: `token = if (previousE164s.isNotEmpty()) cdsToken else
        // null`). They are one pair and travelling alone is meaningless in either direction --
        // a token says "discount the numbers I asked about last time", and paired with an
        // empty list it asserts that last time was nothing, which is not what a token is for.
        val sentToken = token?.takeIf { previouslyAsked.isNotEmpty() }

        // ⚠ A ceiling, because the quota is real and spent per new number. Signal refuses
        // outright above `android.cds.hardLimit` (50,000) and marks itself permanently
        // blocked rather than submit. Nothing here bounded the ask at all: an address book
        // that grew unexpectedly -- an import, a sync gone wrong, a duplicated contacts file
        // -- would be submitted whole and charged for whole.
        if (fresh.size > CDS_HARD_LIMIT) {
            Timber.e(
                "signal discovery: %d new numbers is past the limit of %d; refusing to ask",
                fresh.size, CDS_HARD_LIMIT
            )
            return Result(0, 0, 0, "too many new numbers to look up at once")
        }

        // ⚠⚠ **Read before asking, and refuse to ask without them.** These pairs are what let
        // CDSI answer with account ids at all; sent empty, every answer comes back as a
        // phone-number identity. That was a real bug once and the comment below records it.
        //
        // Defaulting to an empty map on a failed read reintroduced it silently *and made it
        // permanent*: the degraded answers are handed to `state.remember(fresh)` below, which
        // writes every number into `cds_submitted`, and `submitted()` is what excludes them
        // from ever being asked about again. One failed database read would strand every
        // contact in the run as a phone-number identity with nothing left to correct it.
        //
        // This is the same reasoning the failure branch below already uses -- quota grows back
        // where a silently dropped contact does not -- so the answer is the same: do not spend
        // the ask. Upstream passes `recipients.getAllServiceIdProfileKeyPairs()` with no catch
        // at all, so a failure there fails the job and it runs again later; this is that.
        val profileKeyPairs = runCatching { contacts.serviceIdProfileKeyPairs() }.getOrElse {
            Timber.w(it, "signal discovery: could not read the profile keys to ask with; not asking")
            return Result(0, 0, 0, "could not read this phone's own records to ask with")
        }

        // Set when the service hands back a token, which it does only once it has counted the
        // run. That, not a successful answer, is what says the quota was spent.
        var counted = false

        val outcome = CdsApi(connection.authenticated).getRegisteredUsers(
            previouslyAsked,
            fresh,
            // ⚠ Not an empty map, which is what made every answer a phone-number identity.
            //
            // CDSI returns an account id only where the asker can already prove it knows that
            // person -- it takes (ACI, profile key) pairs, turns them into aci/uak pairs, and
            // answers with the ACI for the ones that check out. Sending none guarantees
            // PNI-only results, and the comment below used to say that was unavoidable
            // "because this phone does not have them". It does: they are in the recipient
            // table, put there by the account's own storage records. Signal passes
            // `recipients.getAllServiceIdProfileKeyPairs()` on every request.
            profileKeyPairs,
            Optional.ofNullable(sentToken),
            TIMEOUT_MS,
            connection.network
        ) { issued ->
            counted = true
            runCatching { state.keepToken(issued) }
                .onFailure { Timber.w(it, "signal discovery: the token would not keep") }
        }

        // successOrThrow rather than successOrNull: the failure itself is wanted, because what
        // to tell somebody depends on which one it was -- a spent quota is not a broken
        // connection, and "try again" is the wrong advice for the first.
        val response = runCatching { outcome.successOrThrow() }.getOrElse { failure ->
            Timber.w(failure, "signal discovery: the lookup failed")
            recoverFrom(failure)
            // Deliberately **not** recorded as asked, even when [counted] says the quota was
            // spent. Recording it would make a retry cheap, at the price of marking these
            // numbers permanently answered when nothing ever answered them -- the people
            // behind them would then be missing from the list for good, with nothing saying
            // so. That is the failure this whole feature exists to end, and quota grows back
            // where a silently dropped contact does not. The count is still reported, so a
            // second attempt is a choice made knowing what it costs.
            return Result(0, 0, if (counted) fresh.size else 0, reasonFor(failure))
        }

        runCatching { state.remember(fresh) }
            .onFailure { Timber.w(it, "signal discovery: could not record what was asked") }

        var withoutAci = 0
        val people = response.results.mapNotNull { (e164, item) ->
            val aci = item.aci.orElse(null)
            val pni = item.pni
            if (aci != null && pni != null) {
                // Unverified, in Signal's own terms: its CDS path pairs with
                // `pniVerified = false`, because the service says these two ids go together
                // and nobody has proved it. Kept anyway -- it is the account's own lookup --
                // but see ProtocolStoreSchema.PNI_ACI for what is *not* trusted.
                runCatching { contacts.pair(pni.toString(), aci.toString()) }
            }
            // The account id if the service gave one, the phone-number identity otherwise.
            // CDSI returns an ACI only where the asker already holds a matching ACI/UAK pair,
            // which is why the pairs are now sent above -- this phone does hold them. A PNI is
            // still a real address where it does not, and a message sent to it arrives.
            val id = (aci ?: pni) ?: return@mapNotNull null
            if (aci == null) withoutAci++
            // name = null throughout: this answers who exists, not what they are called. The
            // contact store keeps a name it already has rather than letting a blank overwrite
            // one, and the inbox falls back to the reader's own address book for the rest.
            SignalContactStore.Contact(serviceId = id.toString(), e164 = e164, name = null)
        }
        if (people.isNotEmpty()) contacts.store(people)

        Timber.i(
            "signal discovery: asked %d, found %d, %d of them by phone-number identity (quota used %d)",
            fresh.size, people.size, withoutAci, response.quotaUsedDebugOnly
        )
        return Result(people.size, withoutAci, fresh.size, null)
    }

    /**
     * Puts right what a failure leaves behind, which was nothing.
     *
     * Two of these are not just news, they are state that has to change, and upstream changes
     * it in the same breath as reporting:
     *
     * - **An invalid token** means the token and the set of numbers it stands for have come
     *   apart. Left alone, every later run resends the same bad pair and fails identically --
     *   so "try once more" was advice the app had made impossible to take. Signal nulls the
     *   token and clears the submitted set (`cdsToken = null`, `cds.clearAll()`).
     * - **A spent quota** carries the only number that says when it lifts. Discarding it left
     *   nothing to stop a reader retrying a lookup that cannot succeed, and nothing to tell
     *   them how long. Signal persists `cdsBlockedUtil` from `retryAfterSeconds`.
     */
    private fun recoverFrom(failure: Throwable) {
        val causes = generateSequence(failure) { it.cause }.take(CAUSE_DEPTH).toList()
        val names = causes.joinToString(" ") { it::class.java.simpleName }

        if (names.contains("InvalidToken", true)) {
            Timber.w("signal discovery: the token is out of step; forgetting it and what it stood for")
            runCatching { state.forget() }
                .onFailure { Timber.w(it, "signal discovery: could not forget the token") }
        }

        if (names.contains("ResourceExhausted", true)) {
            val seconds = causes.firstNotNullOfOrNull { cause ->
                runCatching {
                    cause::class.java.methods
                        .firstOrNull { it.name == "getRetryAfterSeconds" && it.parameterCount == 0 }
                        ?.invoke(cause) as? Number
                }.getOrNull()?.toLong()
            }
            if (seconds != null && seconds > 0) {
                val until = System.currentTimeMillis() + seconds * 1000L
                runCatching { state.blockUntil(until) }
                    .onFailure { Timber.w(it, "signal discovery: could not record the block") }
                Timber.w("signal discovery: no more lookups for %d second(s)", seconds)
            }
        }
    }

    /** Said in words somebody can act on, rather than as the exception's own text. */
    private fun reasonFor(failure: Throwable?): String {
        val names = generateSequence(failure) { it.cause }.take(CAUSE_DEPTH)
            .joinToString(" ") { it::class.java.simpleName }
        return when {
            names.contains("ResourceExhausted", true) ->
                "Signal will not answer more lookups for now. That is a limit on the account, " +
                    "not on this phone, and it lifts on its own."
            names.contains("InvalidToken", true) ->
                "The record of what was looked up before is out of step. Try once more."
            names.contains("InvalidArgument", true) -> "Signal refused the list of numbers"
            else -> "The lookup did not finish"
        }
    }

    private companion object {
        /** libsignal's enclave lookup takes its own deadline; without one it can wait forever. */
        private val TIMEOUT_MS = TimeUnit.SECONDS.toMillis(45)

        /** How far down a wrapped exception to look, matching [SignalReceiver]. */
        private const val CAUSE_DEPTH = 5

        /** Signal's `android.cds.hardLimit`. Above this it refuses rather than submits. */
        private const val CDS_HARD_LIMIT = 50_000
    }
}
