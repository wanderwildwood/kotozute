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
        val valid = numbers.filterTo(mutableSetOf()) { it.startsWith("+") && it.length > 3 }
        if (valid.isEmpty()) return Result(0, 0, 0, "There are no numbers to look up")

        val previous = runCatching { state.submitted() }.getOrDefault(emptySet())
        val fresh = valid - previous
        // Not an error and not silence: every one of these has been asked about already, so
        // the answers are in the contact store and another run would pay to be told the same.
        if (fresh.isEmpty()) return Result(0, 0, 0, null)

        connection.connect()
        val token = runCatching { state.token() }.getOrNull()

        // The previous set is only meaningful with the token that covers it: the token is what
        // lets the service discount those numbers, and the two are one pair. Sent without it,
        // the service either counts them all as new -- quota spent on answers already held --
        // or refuses the request outright as an invalid token. Without a token this is a first
        // run, and says so.
        val previouslyAsked = if (token != null) previous else emptySet()

        // Set when the service hands back a token, which it does only once it has counted the
        // run. That, not a successful answer, is what says the quota was spent.
        var counted = false

        val outcome = CdsApi(connection.authenticated).getRegisteredUsers(
            previouslyAsked,
            fresh,
            emptyMap(),
            Optional.ofNullable(token),
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
            // For a linked device that is nearly always the PNI: CDSI returns an ACI only
            // where the asker already holds a matching ACI/UAK pair, which is exactly what
            // this phone does not have. A PNI is still a real address, and a message sent to
            // it arrives.
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
    }
}
