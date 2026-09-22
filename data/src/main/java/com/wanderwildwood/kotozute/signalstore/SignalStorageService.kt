package com.wanderwildwood.kotozute.signalstore

import org.signal.network.NetworkResult
import org.signal.core.models.ServiceId
import org.signal.core.models.storageservice.StorageKey
import org.whispersystems.signalservice.api.storage.RecordIkm
import org.whispersystems.signalservice.api.storage.SignalStorageCipher
import org.whispersystems.signalservice.api.storage.StorageServiceApi
import org.whispersystems.signalservice.internal.storage.protos.ContactRecord
import org.whispersystems.signalservice.internal.storage.protos.ManifestRecord
import org.whispersystems.signalservice.internal.storage.protos.ReadOperation
import org.whispersystems.signalservice.internal.storage.protos.StorageRecord
import timber.log.Timber

/**
 * One contact record as the account holds it: what it says, the bytes it came as, and the id
 * it is filed under.
 *
 * A triple of unrelated things would read as one at every call site; this says which is which.
 */
internal data class RemoteContact(
    val record: ContactRecord,
    val raw: ByteArray,
    val storageId: String
)

/**
 * Reading the account's contact list out of Signal's storage service.
 *
 * This exists because of one report from somebody other than the author: the new-message list
 * showed only SMS contacts. The list is built from whoever the primary has told this phone
 * about, and the way it was told -- a contacts sync -- is a mechanism **modern Signal Android
 * no longer uses**. It keeps contacts in an encrypted store on Signal's servers instead, and
 * a linked device is expected to read them from there. Without this, the feature works for a
 * signal-cli primary and for nobody else.
 *
 * The shape: ask for an auth token, fetch the manifest, decrypt it with a key derived from
 * the manifest's own version, read the records it names, decrypt each with a key derived from
 * that record's id, and keep the contacts. Nothing is written back -- this device reads the
 * account's state and never edits it.
 */
internal class SignalStorageService(
    private val connection: SignalConnection,
    private val keys: SignalKeyStore,
    private val contacts: SignalContactStore,
    /**
     * Who this account is, so a record describing it can be refused.
     *
     * ⚠ "You can't have a contact record for yourself. That should be an account record" --
     * Signal's own words, on `ContactRecordProcessor.isInvalid`. Nothing here checked, so the
     * account owner could be filed as one of their own contacts, carrying the account's own
     * number and phone-number identity. Only the account id was filtered, much further
     * downstream and only as a string, so a self record whose id was written differently
     * arrived as a stranger -- and its number then became fuel for a merge.
     */
    private val self: Self = Self(null, null, null),
    /**
     * Records the identity key the account already holds for somebody, and whether it has been
     * verified. Every contact record carries both, and they were decrypted and dropped.
     */
    private val identities: (String, org.signal.libsignal.protocol.IdentityKey, IdentityState) -> Unit =
        { _, _, _ -> },
    /**
     * Who the account has blocked, as its own records say.
     *
     * ⚠ This is **where blocking actually lives** on a current account, and it was not being
     * read at all. Every contact record carries a `blocked` flag and every group record carries
     * one too; this app implemented only the legacy `SyncMessage.Blocked` list, which a modern
     * primary need never send. So somebody the account owner had blocked went on arriving here:
     * decrypted, filed, shown, announced -- and answered with a delivery receipt, which tells
     * the blocked person the phone is on and reading them.
     *
     * Called with the whole picture, not a delta, because that is what a storage read is: the
     * account's current list, which replaces whatever was held. That also repairs a list left
     * stale by an old sync, since nothing else ever refreshes one.
     */
    private val blocked: (List<SignalBlockStore.Blocked>, List<ByteArray>) -> Unit = { _, _ -> },
    /**
     * The account's own profile key, as its own record states it.
     *
     * ⚠ This phone sends its profile key out with **every message**, so the recipient can read
     * the name and avatar behind it. It was read once from the provisioning message at link
     * time and never again -- and the account rotates it: Signal generates a new one whenever
     * somebody is hidden with no groups in common, among other paths. After that, this device
     * went on handing everybody a key that no longer opens anything, so its own contact would
     * quietly stop being able to see who was writing to them.
     *
     * The record was not even fetched: the manifest request asked for contacts and groups
     * only. `AccountRecordProcessor` takes the remote key over the local one for the same
     * reason this does -- a linked device has no business having an opinion about the
     * account's profile key.
     */
    private val onProfileKey: (ByteArray) -> Unit = {},
    /**
     * Muted and archived, as the account holds them, per conversation.
     *
     * Both live in the same records as blocking and were read no more than it was. Muting a
     * conversation in Signal left it ringing on this phone, and archiving one left it in the
     * inbox -- with nothing to say the account had been told otherwise.
     */
    private val conversationState: (List<ConversationState>) -> Unit = { _ -> }
) {

    /** What the account says about a conversation, beyond who is in it. */
    data class ConversationState(
        val threadKey: String,
        val muted: Boolean,
        val archived: Boolean
    )

    /**
     * What a read did, for a status line to say and a log to carry.
     *
     * The drop counts are not decoration. Every record this cannot use was previously
     * skipped by a `mapNotNull` that returned null, so a fetch that understood a third of
     * the account reported the same shape of success as one that understood all of it --
     * and the only visible symptom was people missing from a list nobody could check
     * against. [contacts] + [unopened] + [notContacts] + [anonymous] accounts for every
     * record in [records]; if it ever does not, something new is being dropped.
     */
    data class Result(
        val contacts: Int,
        val records: Int,
        val reason: String? = null,
        /** Would not decrypt, or would not decode once decrypted. */
        val unopened: Int = 0,
        /** Opened, but held something other than a contact. */
        val notContacts: Int = 0,
        /** A contact naming no address at all -- neither an account id nor a PNI. */
        val anonymous: Int = 0,
        /** Kept under a phone-number identity, because no account id was on the record. */
        val pniOnly: Int = 0,
        /** Records the service would not hand over at all, so this read is incomplete. */
        val unreadable: Int = 0,
        /**
         * Of [anonymous], those carrying a phone number.
         *
         * The one fact that decides what can be done about them: a number is what contact
         * discovery takes, so these are recoverable. One with neither an account id nor a
         * number is not addressable by anything this app could ask for.
         */
        val anonymousWithNumber: Int = 0
    )

    /** The account's own three identifiers, any of which may be absent. */
    data class Self(val aci: String?, val pni: String?, val e164: String?)

    /**
     * What the account says about somebody's safety number.
     *
     * Signal's three `VerifiedStatus` values, and they are genuinely three:
     * [Verified] the owner has checked it in person; [Unverified] the owner has explicitly
     * said it is not verified, which stops sends until it is approved; [Default] neither, the
     * ordinary state. Upstream's `remoteToLocalIdentityStatus` maps the record's field onto
     * exactly these.
     */
    enum class IdentityState { Verified, Unverified, Default }

    /** See the companion's [invalidReason]; this one just supplies [self]. */
    private fun invalidReason(aci: String?, pni: String?, e164: String?): String? =
        invalidReason(self, aci, pni, e164)

    /**
     * @param onWritable handed the manifest this read was holding, once the read has finished
     *   cleanly, so a write can be computed against it in the same pass. Null -- the default
     *   -- is a read that writes nothing, which is every caller until the write flag is on.
     */
    fun read(
        onWritable: ((Long, List<ManifestRecord.Identifier>, org.whispersystems.signalservice.api.storage.RecordIkm?) -> Unit)? = null
    ): Result {
        val storageKey = keys.storageKey()
            ?: return Result(0, 0, "the storage key is not here yet")

        connection.connect()
        val api = StorageServiceApi(connection.authenticated, connection.push)

        val auth = api.getAuth().successOrNull()
            ?: return Result(0, 0, "the service would not give an auth token")

        // ⚠ **A 404 here is not a failure.** The vendored API documents it as "No storage
        // manifest was found", and that is the ordinary state of an account nobody has ever
        // written records for -- which is exactly what **registering** produces. Reported as
        // "the manifest could not be read" it looks like a fault on every sync of a healthy
        // new account, and sends the reader looking for a broken key or a bad token.
        //
        // Said apart, not fixed: writing the first manifest is the storage **write** path,
        // which `docs/DECISION-storage-write.md` stages deliberately and which needs unknown
        // fields carried through first. Nothing here writes.
        val manifestResult = api.getStorageManifest(auth)
        val manifest = manifestResult.successOrNull() ?: return Result(
            0,
            0,
            if (manifestResult is NetworkResult.StatusCodeError && manifestResult.code == 404) {
                "this account has no stored records yet"
            } else {
                "the manifest could not be read"
            }
        )

        val manifestRecord = runCatching {
            ManifestRecord.ADAPTER.decode(
                SignalStorageCipher.decrypt(
                    storageKey.deriveManifestKey(manifest.version), manifest.value_.toByteArray()
                )
            )
        }.getOrElse {
            // The likeliest cause by far is the wrong key, which means the pool this device
            // was given is not this account's. Said plainly rather than as a crypto error.
            Timber.w(it, "signal storage: the manifest would not open")
            return Result(0, 0, "the manifest would not open with this key")
        }

        // Newer accounts derive each record's key from a value carried by the manifest rather
        // than from the storage key itself. Both are live; which one applies is decided here
        // and nowhere else.
        val ikm = manifestRecord.recordIkm
            ?.takeIf { it.size > 0 }
            ?.let { RecordIkm(it.toByteArray()) }

        // Contacts **and** groups. Only contacts were asked for, which meant every group
        // record in the account went unread -- and a group record is where the account keeps
        // whether that group is blocked or muted. A group blocked in Signal went on arriving
        // here, and there was nothing in the manifest request that would ever have said so.
        val wanted = manifestRecord.identifiers
            .filter {
                it.type == ManifestRecord.Identifier.Type.CONTACT ||
                    it.type == ManifestRecord.Identifier.Type.GROUPV2 ||
                    // ⚠ And the account's own record, which was never asked for.
                    //
                    // It is where the account keeps **its own profile key**, and this phone
                    // sends that key out with every message so the recipient can read the
                    // profile behind it. Ours was read once from the provisioning message and
                    // never again, so a rotation on the primary -- which Signal does whenever
                    // somebody is hidden with no groups in common -- left this device handing
                    // everybody a key that no longer opens anything. See [onProfileKey].
                    it.type == ManifestRecord.Identifier.Type.ACCOUNT
            }
            .mapNotNull { it.raw }
        if (wanted.isEmpty()) return Result(0, 0, null)

        var kept = 0
        var seen = 0
        var unopened = 0
        var notContacts = 0
        /** Records the account should not have sent; see [invalidReason]. */
        var invalid = 0
        var anonymous = 0
        var pniOnly = 0
        var anonymousWithNumber = 0
        var unreadable = 0
        // Who the account's own records say is blocked. Gathered whole and applied once, below.
        val blockedPeople = mutableListOf<SignalBlockStore.Blocked>()
        val blockedGroups = mutableListOf<ByteArray>()
        val conversations = mutableListOf<ConversationState>()
        var groupsSeen = 0
        var accountsSeen = 0
        // "Muted until" is a moment, not a flag: Signal stores when it ends, and a very large
        // value is how it says "for good". Compared against now rather than treated as a
        // boolean, or a mute that expired last year would still be silencing the conversation.
        val now = System.currentTimeMillis()
        // In batches: a manifest can name thousands of records, and the service takes a list
        // of ids per request rather than all of them.
        wanted.chunked(BATCH).forEach { batch ->
            val items = api.readStorageItems(auth, ReadOperation(readKey = batch)).successOrNull()
                ?: run {
                    // A 5xx, a timeout or an expired auth token loses up to two hundred people
                    // here. Swallowed, a partial read reported as a complete one -- the exact
                    // shape of the bug this whole counter block exists because of.
                    unreadable += batch.size
                    Timber.w("signal storage: a batch of %d record(s) could not be read", batch.size)
                    return@forEach
                }
            seen += items.items.size
            val found = items.items.mapNotNull { item ->
                val id = item.key.toByteArray()
                val itemKey = ikm?.deriveStorageItemKey(id) ?: storageKey.deriveItemKey(id)
                val record = runCatching {
                    StorageRecord.ADAPTER.decode(
                        SignalStorageCipher.decrypt(itemKey, item.value_.toByteArray())
                    )
                }.getOrElse { unopened++; null } ?: return@mapNotNull null

                // A group record. Not a contact, and not a record this cannot use -- so it is
                // taken here rather than counted as a miss.
                record.groupV2?.let { group ->
                    groupsSeen++
                    groupIdOf(group.masterKey?.toByteArray())?.let { groupId ->
                        if (group.blocked) blockedGroups += groupId
                        conversations += ConversationState(
                            threadKey = "group:" + android.util.Base64.encodeToString(
                                groupId, android.util.Base64.NO_WRAP
                            ),
                            muted = group.mutedUntilTimestamp > now,
                            archived = group.archived
                        )
                    }
                    return@mapNotNull null
                }
                // The account's own record. Not a contact -- and refusing a *contact* record
                // that describes this account, which [invalidReason] does, is a different
                // rule: that one is about a record filed under the wrong type. This is the
                // right type, and it is the only record that is legitimately about us.
                record.account?.let { account ->
                    accountsSeen++
                    account.profileKey?.takeIf { it.size > 0 }?.let { key ->
                        runCatching { onProfileKey(key.toByteArray()) }
                            .onFailure {
                                // The whole manifest is re-read from scratch on every trigger
                                // -- there is no version check that would skip it -- so the
                                // next read of the account's records offers this key again.
                                // Nothing else in the run depends on it having been kept.
                                Timber.w(it, "signal storage: a profile key would not keep; the next read offers it again")
                            }
                    }
                    return@mapNotNull null
                }

                // ⛔ The **whole** record travels on, not just the contact half. Step 0 of
                // `docs/DECISION-storage-write.md`: these bytes are what a write must start
                // from, so that fields this build has never heard of -- written by a newer
                // Signal client -- survive a re-encode instead of being erased for every
                // device on the account.
                record.contact?.let {
                    RemoteContact(it, record.encode(), android.util.Base64.encodeToString(id, android.util.Base64.NO_WRAP))
                } ?: run { notContacts++; null }
            }
            val people = found.mapNotNull { (record, rawRecord, remoteStorageId) ->
                val aci = aciOf(record)
                val pni = pniOf(record)
                val e164 = record.e164?.takeIf { it.isNotBlank() }

                // ⚠ Before anything is learned from it, and before any pairing is written.
                //
                // A record this app should not have been sent is not a record to take the
                // good parts of: the pairing below writes a pni-to-aci binding on the
                // account's authority, and an identity key adoption below that decides what
                // key this device will trust. Both of those are worth refusing outright.
                invalidReason(aci, pni, e164)?.let { why ->
                    Timber.w("signal storage: ignoring a contact record -- %s", why)
                    invalid++
                    return@mapNotNull null
                }

                // A record carrying both is the account telling this phone, on its own
                // authority, that these two ids are one person. It is the only pairing that
                // is trusted here -- see the note on ProtocolStoreSchema.PNI_ACI -- and it is
                // what later folds a conversation held under the PNI into the real one.
                if (aci != null && pni != null) {
                    runCatching { contacts.pair(pni, aci) }
                        .onFailure {
                            // One record, not the run: the remaining contacts are still read.
                            // An unpaired PNI and ACI mean that person may appear twice until
                            // the next storage read offers the pairing again, which the whole
                            // manifest does every time it is read.
                            Timber.w(it, "signal storage: a pairing would not keep; the next read offers it again")
                        }
                }

                // The account id where there is one, the phone-number identity otherwise.
                // Somebody who has turned off "who can find me by number" is known to Signal
                // by their PNI and nothing else, and that is a real address -- messages sent
                // to it arrive. Dropping them was why two thirds of one account's contacts
                // could not be written to.
                val id = aci ?: pni

                // The account already knows what key it holds for this person, and it was
                // being decrypted and thrown away -- leaving this device to accept whatever
                // key the server offered in the first prekey bundle. A trust-on-first-use
                // window on a device that did not need one.
                if (id != null) {
                    record.identityKey?.takeIf { it.size > 0 }?.let { key ->
                        runCatching {
                            identities(
                                id,
                                org.signal.libsignal.protocol.IdentityKey(key.toByteArray()),
                                // ⚠ Three states, not a boolean. Collapsing it to
                                // "is it VERIFIED" made UNVERIFIED mean the same as DEFAULT,
                                // and they are not the same: DEFAULT is an ordinary contact,
                                // UNVERIFIED is one the account owner has explicitly marked
                                // as not verified, and upstream stops sending to those until
                                // somebody approves it (`isTrustedForSending` returns false on
                                // UNVERIFIED). Dropping the distinction silently un-did that
                                // decision on this device.
                                when (record.identityState) {
                                    ContactRecord.IdentityState.VERIFIED -> IdentityState.Verified
                                    ContactRecord.IdentityState.UNVERIFIED -> IdentityState.Unverified
                                    else -> IdentityState.Default
                                }
                            )
                        }.onFailure {
                            // ⚠ Their safety number stays as this phone last knew it, which is
                            // the safe direction: an identity that fails to store cannot
                            // silently become trusted, and the next read offers it again.
                            Timber.w(it, "signal storage: an identity would not keep; the next read offers it again")
                        }
                    }
                }

                if (id == null) {
                    anonymous++
                    if (!record.e164.isNullOrBlank()) anonymousWithNumber++
                    return@mapNotNull null
                }
                if (aci == null) pniOnly++
                // The flag that says this person is blocked. One boolean, and the whole of
                // modern blocking; see the [blocked] parameter.
                conversations += ConversationState(
                    threadKey = "direct:$id",
                    muted = record.mutedUntilTimestamp > now,
                    archived = record.archived
                )
                if (record.blocked) {
                    blockedPeople += SignalBlockStore.Blocked(
                        aci = id,
                        e164 = e164,
                        // ⚠ Was hard-coded to zero while the record carried the real value.
                        // The zeroes did not stay local: blocking anybody from this phone
                        // republishes the whole list as a legacy blocked sync, and the
                        // primary's `applyBlockedUpdate` writes what it is sent straight over
                        // its own timestamps -- so one block here erased, account-wide, when
                        // every other block had happened.
                        blockedAt = record.blockedAtTimestamp
                    )
                }
                SignalContactStore.Contact(
                    serviceId = id,
                    // Both ids on one record is the account saying they are one person.
                    pni = pni,
                    e164 = e164,
                    name = nameOf(record),
                    // Their own profile's name as the account holds it, apart from what they
                    // are shown as. Signal writes it from the record too, so a fetch that
                    // follows does not announce a change the account already knew about.
                    profileName = ProfileNames.joined(record.givenName, record.familyName),
                    profileKey = record.profileKey?.takeIf { it.size > 0 }?.toByteArray(),
                    // Not a name -- Signal shows it only once there is no name and no number
                    // -- but the last thing between this person and a row of hexadecimal.
                    username = record.username?.takeIf { it.isNotBlank() },
                    // ⚠ Two fields the account sets to say "do not offer this person", both
                    // decoded and thrown away. `hidden` is the owner's deliberate choice, and
                    // a hidden contact reappeared at every storage read because of it.
                    // `unregisteredAtTimestamp` is the account's note that they have left
                    // Signal, and offering them starts a conversation that can never deliver.
                    // Signal keeps both and excludes them from contact search.
                    hidden = record.hidden,
                    unregisteredAt = record.unregisteredAtTimestamp,
                    // What the account actually holds for this row, kept whole. See
                    // [SignalContactStore.Contact.storageRecord] -- a write starts from these
                    // bytes so that fields this build cannot parse are put back untouched.
                    storageRecord = rawRecord,
                    // Which manifest entry this row is. A write replaces exactly this id and
                    // touches no other. See schema v35.
                    remoteStorageId = remoteStorageId
                ).also {
                    // Whether the account shares its profile with them. Applied here rather
                    // than carried through the merge, because it is a fact about the
                    // relationship and not about which row somebody belongs in.
                    runCatching { contacts.setWhitelisted(id, record.whitelisted) }
                        .onFailure { e ->
                            // ⚠ The flag stays as this phone last knew it, which is the safe
                            // direction: a sharing flag that fails to store cannot silently
                            // turn into sharing. The whole manifest is read again on every
                            // storage read, so the next one offers this record afresh.
                            Timber.w(e, "signal storage: a profile-sharing flag would not keep; the next read offers it again")
                        }
                }
            }
            if (people.isNotEmpty()) {
                contacts.store(people)
                kept += people.size
            }
        }
        // ⚠ The kept counts and the dropped counts are said separately, and they were not.
        // `pniOnly` counts records that were *kept*; it sat inside the parenthetical after
        // `anonymous`, which counts records that were *dropped* -- so "0 anonymous (76
        // pni-only, 0 with a number)" read as though 76 contacts had been thrown away and
        // none of them had a phone number. Neither half of that was true, and the line is the
        // only thing a release build says about this.
        // ⚠ Only when the read was complete. This replaces the blocked list wholesale, and a
        // partial read would replace it with a short one -- which does not fail safe: it
        // **unblocks** whoever was in the batch that did not arrive, silently, and the next
        // message from them lands in the inbox as though nothing had been decided.
        if (unreadable == 0) {
            runCatching { blocked(blockedPeople, blockedGroups) }
                .onFailure { Timber.w(it, "signal storage: the blocked list would not keep") }
            runCatching { conversationState(conversations) }
                .onFailure { Timber.w(it, "signal storage: muted and archived would not keep") }
        } else {
            Timber.w(
                "signal storage: %d record(s) unread, so the blocked list is left as it was",
                unreadable
            )
        }

        if (groupsSeen > 0 || blockedGroups.isNotEmpty()) {
            Timber.i(
                "signal storage: %d group record(s), %d of them blocked", groupsSeen, blockedGroups.size
            )
        }
        Timber.i(
            "signal storage: %d contact(s) from %d record(s), %d of them known only by phone-number identity; " +
                "dropped %d unopened, %d not contacts, %d with no id at all (%d of those had a " +
                "number), %d the account should not have sent; %d account record(s) read",
            kept, seen, pniOnly, unopened, notContacts, anonymous, anonymousWithNumber, invalid,
            accountsSeen
        )
        // ⚠ **The write goes here, in the same pass, or not at all.**
        //
        // A locally-rotated id goes up as an insert while the stale id it replaced is deleted
        // in the same write. Split into two passes, the stale record survives to be applied by
        // the next read and quietly reverts the local change -- which `logStoragePushDiff`
        // watched happen before any write existed. This is also why the writer takes the
        // manifest this read was holding rather than fetching its own.
        //
        // ⛔ Only when a read was **complete**. Writing a manifest computed from a partial read
        // is how entries get dropped, and a dropped entry is somebody else's data.
        if (unreadable == 0) {
            onWritable?.invoke(manifest.version, manifestRecord.identifiers, ikm)
        }

        return Result(kept, seen, null, unopened, notContacts, anonymous, pniOnly, unreadable, anonymousWithNumber)
    }

    companion object {
        /**
         * What a phone number on a storage record is allowed to look like.
         *
         * Signal's `ContactRecordProcessor.E164_PATTERN`, and deliberately not the stricter one
         * registration uses: this is a number somebody else's client wrote, possibly years
         * ago, and the job here is to refuse junk rather than to re-decide what a valid number
         * is.
         *
         * ⚠ Without it a legacy row, a short code or a bare unprefixed string became a row key
         * -- and a key is exactly what a number is here. Two records carrying the same junk
         * both match a lookup by number and are taken for one person.
         */
        private val E164_PATTERN = Regex("""^\+[1-9]\d{6,18}$""")

        /**
         * Whether this record is one the account should never have sent, and must not be
         * stored. Null when it is fine.
         *
         * Signal's `ContactRecordProcessor.isInvalid`, case for case. It refuses the record
         * whole rather than repairing it, and that is the part worth keeping: a record that is
         * wrong about who somebody is cannot be made right by dropping the wrong field,
         * because nothing says which field is the wrong one.
         *
         * Internal so it can be tested without a network or a store. On a healthy account it
         * never fires, and a check that never fires is not evidence of anything.
         */
        internal fun invalidReason(
            self: Self,
            aci: String?,
            pni: String?,
            e164: String?
        ): String? = when {
            aci == null && pni == null -> "neither an account id nor a phone-number identity"
            self.aci != null && self.aci == aci -> "it describes this account"
            self.pni != null && self.pni == pni -> "it describes this account"
            self.e164 != null && e164 != null && e164 == self.e164 -> "it describes this account"
            e164 != null && !E164_PATTERN.matches(e164) -> "a phone number that is not one"
            else -> null
        }

        private const val BATCH = 200

        /** A group master key is 32 bytes; anything else is not one, whatever it decodes to. */
        private const val GROUP_MASTER_KEY_SIZE = 32

        /**
         * The account id on a contact record, from whichever field carries it.
         *
         * Two fields hold the same value and a client populates one of them: `aci` is the
         * old hyphenated string, and modern Signal writes the same account as raw bytes in
         * `aciBinary` and leaves the string empty. Reading only the string is the same trap
         * the sent transcript fell into -- see `ContentNormalizer.destinationServiceIdOf`,
         * which was fixed for exactly this reason and in exactly this way.
         *
         * Measured on a real account before the fix: **71 contacts out of 201 records**. The
         * other 130 were written by a modern primary, carried their account in `aciBinary`,
         * and were dropped without a word -- which is why somebody could not find a friend
         * who was demonstrably on Signal.
         */
        fun aciOf(record: ContactRecord): String? {
            val text = record.aci?.takeIf { it.isNotBlank() }
            val binary = record.aciBinary?.takeIf { it.size > 0 }
            if (text == null && binary == null) return null
            return ServiceId.ACI.parseOrNull(text, binary)?.toString()
        }

        /**
         * The phone-number identity on a contact record, read the same two ways.
         *
         * Only counted, never stored. A PNI is a real address Signal can send to, but the
         * rest of this app keys people on an ACI, and writing a `PNI:` id into that column
         * would make a person this device cannot actually resolve look like one it can.
         * Worth knowing how many there are before deciding what to do about them.
         */
        fun pniOf(record: ContactRecord): String? {
            val text = record.pni?.takeIf { it.isNotBlank() }
            val binary = record.pniBinary?.takeIf { it.size > 0 }
            if (text == null && binary == null) return null
            return ServiceId.PNI.parseOrNull(text, binary)?.toString()
        }

        /**
         * The id a group thread is keyed by, derived from the master key its record carries.
         *
         * The same derivation a live group message goes through, so a group blocked in the
         * account's records matches the thread this phone already holds.
         */
        fun groupIdOf(masterKey: ByteArray?): ByteArray? {
            if (masterKey == null || masterKey.size != GROUP_MASTER_KEY_SIZE) return null
            return runCatching {
                org.signal.libsignal.zkgroup.groups.GroupSecretParams
                    .deriveFromMasterKey(
                        org.signal.libsignal.zkgroup.groups.GroupMasterKey(masterKey)
                    )
                    .publicParams
                    .groupIdentifier
                    .serialize()
            }.getOrNull()
        }

        /**
         * What to call this person, in Signal's own order of preference.
         *
         * ⚠ The **nickname** comes first, and was not being read at all. It is the name the
         * account owner has typed for this person themselves, in Signal, overriding everything
         * else -- which is exactly why Signal ranks it above the address book and above the
         * profile. Skipping it meant a contact deliberately renamed showed up here under a
         * different name than the one their own phone shows, or, for somebody with no other
         * name and no number, under no name at all.
         *
         * `systemNickname` is deliberately not in this chain: Signal stores it but does not
         * display it, and putting it here would be inventing an order rather than copying one.
         */
        fun nameOf(record: ContactRecord): String? {
            val nickname = joined(record.nickname?.given, record.nickname?.family)
            if (nickname != null) return nickname
            val system = joined(record.systemGivenName, record.systemFamilyName)
            if (system != null) return system
            return joined(record.givenName, record.familyName)
        }

        /**
         * Given and family into one name, or null when there is nothing to join.
         *
         * The same rule the profile fetch uses, because it is the same two fields.
         *
         * These two paths had each invented their own join, so a CJKV contact could read one
         * way from a profile fetch and the other way from a storage record.
         */
        private fun joined(given: String?, family: String?): String? =
            ProfileNames.joined(given, family)
    }
}
