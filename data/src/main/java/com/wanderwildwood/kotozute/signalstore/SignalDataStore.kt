package com.wanderwildwood.kotozute.signalstore

import org.signal.core.models.ServiceId
import org.whispersystems.signalservice.api.SignalServiceAccountDataStore
import org.whispersystems.signalservice.api.SignalServiceDataStore

/**
 * The account's two protocol states -- ACI and PNI -- and the thing the service layer is
 * constructed with.
 *
 * Two of everything, not one. The ACI is the account's real identity; the PNI is the separate
 * identity tied to the phone number, and it exists so that someone who only knows the number
 * cannot learn the account behind it. They have different identity key pairs, different
 * registration ids and different sessions, and they are not interchangeable: a message
 * addressed to one and answered from the other produces a session the far end cannot decrypt.
 *
 * All of it lives in one database, partitioned by `account_id_type`. Each store here is bound
 * to its own value of that column, and every table except `identity` and the sender-key ones
 * carries it -- the exceptions matching signal-cli, where a peer's identity key and a sender
 * key belong to the peer, not to which of our identities is asking.
 */
internal class SignalDataStore(
    private val db: ProtocolDatabase,
    private val accounts: SignalAccountStore
) : SignalServiceDataStore {

    private val aciStore = storeFor(ProtocolDatabase.ACCOUNT_ID_TYPE_ACI)
    private val pniStore = storeFor(ProtocolDatabase.ACCOUNT_ID_TYPE_PNI)

    override fun aci(): SignalServiceAccountDataStore = aciStore

    /**
     * The same store, as the type it actually is.
     *
     * [aci] answers with libsignal's interface because that is what libsignal is handed. A
     * couple of things here are this app's own -- recording a verification, for one -- and
     * they are not on that interface.
     */
    fun aciStore(): SignalAccountDataStore = aciStore

    /**
     * Throws when the account has no PNI, which is signal-cli's behaviour and the right one:
     * the callers that reach for this are on paths that require a phone number, and handing
     * them the ACI store instead would sign with the wrong identity somewhere far from here.
     */
    override fun pni(): SignalServiceAccountDataStore =
        pniOrNull() ?: throw IllegalStateException("the account has no PNI")

    override fun pniOrNull(): SignalServiceAccountDataStore? = pniStore.takeIf { identifiers().pni != null }

    /**
     * Resolves by the stored value, not by the type of the argument.
     *
     * The difference matters. Matching on `is ServiceId.ACI` would happily hand back this
     * account's store for somebody else's ACI, and the resulting session would be built with
     * the wrong identity key -- undecryptable at the far end, with nothing pointing here. An
     * unrecognised identifier is a programming error, so it fails immediately.
     */
    override fun get(accountIdentifier: ServiceId): SignalServiceAccountDataStore {
        val (aci, pni) = identifiers()
        return when (accountIdentifier) {
            aci -> aciStore
            pni -> pniStore
            else -> throw IllegalArgumentException("no protocol store for $accountIdentifier")
        }
    }

    private data class Identifiers(val aci: ServiceId.ACI?, val pni: ServiceId.PNI?)

    /**
     * The account's own identifiers, parsed rather than compared as text.
     *
     * Parsing is not fussiness: a PNI is written `PNI:<uuid>` in some places and bare in
     * others -- the provisioning message carries the bare form, which is what [DeviceLinker]
     * stores -- so comparing strings would fail to match the account's own PNI and every
     * lookup for it would throw. `ServiceId.PNI.parseOrNull` normalises both.
     *
     * Cached once complete, because [get] is called per message and the account is written
     * exactly once, at linking. Before that it is re-read, so a store built before linking
     * starts working the moment linking finishes rather than staying empty for the process.
     */
    @Volatile private var cached: Identifiers? = null

    private fun identifiers(): Identifiers = cached ?: run {
        val credentials = accounts.credentials()
        val identifiers = Identifiers(
            ServiceId.ACI.parseOrNull(credentials.aci),
            ServiceId.PNI.parseOrNull(credentials.pni)
        )
        if (identifiers.aci != null) cached = identifiers
        identifiers
    }

    override fun isMultiDevice(): Boolean = accountHasOtherDevices()

    /**
     * Whether this account has any device on it besides this one.
     *
     * ⛔ **This used to be the literal `true`, with the note "a linked device is never alone on
     * the account".** That was sound while linking was the only way in. **Standalone
     * registration made it false**: a phone that registers its own account is the only device
     * on it, and saying otherwise has consequences the library acts on.
     *
     * The one that bites is Note to Self. A note to self *is* a sync transcript to your other
     * devices, so `SignalServiceMessageSender.sendSyncMessage` short-circuits to success and
     * sends nothing when `!isMultiDevice` (`SignalServiceMessageSender.java:730`). Claiming
     * otherwise meant the message really went to the server, addressed to this very device,
     * and the server answered **403** -- which surfaced as "Authorization failed" and looked
     * like broken credentials rather than a message that should never have been sent.
     *
     * ⚠ The session test is a lower bound, not a census. A device we have never exchanged a
     * message with leaves no session, so a primary can under-report right after another device
     * links to it. That is the safe direction and the library covers it: every send also ORs
     * in `needsSyncInResults`, which comes from what the *server* said about our account, so a
     * real sibling still gets its transcript on the first send after it appears.
     */
    private fun accountHasOtherDevices(): Boolean = runCatching {
        val credentials = accounts.credentials()
        // Being a linked device is proof on its own: something linked us, and that something
        // is the primary. No session needed to know it exists.
        if (credentials.deviceId != SignalSessionStore.PRIMARY_DEVICE_ID) return@runCatching true

        // Nullable: an account row exists before it has an identity. No aci is no siblings.
        val selfAci = credentials.aci
        if (selfAci.isNullOrBlank()) return@runCatching false
        SignalSessionStore(db, ProtocolDatabase.ACCOUNT_ID_TYPE_ACI)
            .deviceIdsFor(selfAci)
            .any { it != credentials.deviceId }
    }
        // A store that will not answer is not evidence of siblings. False is the safe way to
        // be wrong: it withholds a sync transcript the server will ask for anyway, where true
        // sends a message to a device that is not there.
        .getOrDefault(false)

    private fun storeFor(accountIdType: Int) = SignalAccountDataStore(
        db,
        accountIdType,
        SignalIdentityKeyStore(db, accountIdType),
        SignalSessionStore(db, accountIdType),
        SignalPreKeyStore(db, accountIdType),
        SignalSignedPreKeyStore(db, accountIdType),
        SignalKyberPreKeyStore(db, accountIdType),
        SignalSenderKeyStore(db),
        // One answer for both stores. They implement the same method on the same account, and
        // two literals is how they came to disagree with reality together.
        hasOtherDevices = ::accountHasOtherDevices
    )
}
