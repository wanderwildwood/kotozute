package com.wanderwildwood.kotozute.signalstore

import okio.ByteString.Companion.toByteString
import org.whispersystems.signalservice.api.storage.RecordIkm
import org.whispersystems.signalservice.api.storage.SignalStorageCipher
import org.whispersystems.signalservice.api.storage.StorageServiceApi
import org.whispersystems.signalservice.internal.storage.protos.ManifestRecord
import org.whispersystems.signalservice.internal.storage.protos.StorageItem
import org.whispersystems.signalservice.internal.storage.protos.StorageManifest
import org.whispersystems.signalservice.internal.storage.protos.StorageRecord
import org.whispersystems.signalservice.internal.storage.protos.WriteOperation
import timber.log.Timber

/**
 * Writing this device's decisions back to the account's storage service.
 *
 * **Step 3 of `docs/DECISION-storage-write.md`.** Read that first; it is the record of why
 * this is shaped the way it is rather than the obvious way.
 *
 * ## What this deliberately does not do
 *
 * ⛔ **It never computes deletes as "remote ids minus local ids".** That is the obvious diff
 * and it would erase the account's records for every device on it. This app models contacts
 * and groups; an account's manifest also names story distribution lists, call links, chat
 * folders, sticker packs, notification profiles and the account's own record, none of which
 * have a row here. Signal can do that subtraction only because it keeps
 * `unknownStorageIds.allUnknownIds` and folds them back in
 * (`StorageSyncJob.getAllLocalStorageIds`); this app keeps no such table.
 *
 * So every delete is **the one id the row being replaced arrived under**, read from
 * `recipient.remote_storage_id` (schema v35). Every other entry in the manifest is carried
 * across untouched. The cost is that a genuinely orphaned record is never tidied up, which is
 * the conservative direction to be wrong in.
 *
 * ⛔ **It never builds a record from this app's fields.** Each insert starts from the bytes
 * the account actually holds (`recipient.storage_record`, schema v34), decodes them, sets the
 * handful of fields this device decides, and re-encodes -- so fields written by a newer
 * Signal client ride through instead of being destroyed. That is step 0, and it is why step 0
 * came first.
 *
 * ## Why it must run in the same pass as a read
 *
 * A locally-rotated id goes up as an insert while the stale id it replaced is deleted in the
 * same write. Split apart, the stale record survives to be applied on the next read and
 * quietly reverts the local change -- which `logStoragePushDiff` observed happening before any
 * write existed. So this takes the manifest it was given by the read that just ran, and is not
 * callable on its own.
 */
internal class SignalStorageWriter(
    private val connection: SignalConnection,
    private val contacts: SignalContactStore,
    private val keys: SignalKeyStore
) {

    /**
     * What this device wants a row to say, for the fields it is allowed to decide.
     *
     * Supplied by the caller because they live in three different places -- blocking in the
     * protocol store, mute and archive in Realm -- and none of them belong to this class.
     */
    data class Desired(
        /** Whether it is muted at all. See [mutedUntilFor] for what goes on the wire. */
        val muted: Boolean,
        val archived: Boolean,
        /**
         * Null keeps what the account's record says, which is what every caller passes today.
         * A read replaces the phone's block list with the account's before any write can run,
         * and a block made here already reaches the primary as its own sync message -- writing
         * it through storage as well would be two mechanisms deciding one thing.
         */
        val blocked: Boolean? = null
    )

    /** One row that went up: the id it went up under, and the record it went up as. */
    class Pushed(val storageId: String, val record: ByteArray)

    sealed interface Outcome {
        /** Nothing was marked. The ordinary case, and not a failure. */
        data object NothingToDo : Outcome

        /**
         * Marked rows whose record already says what this device wants -- the account caught
         * up another way, or a later read brought the same value back. Nothing to send; the
         * caller clears their marks so they stop coming round.
         */
        data class AlreadyThere(val storageIds: List<String>) : Outcome

        /** What would have gone up. Returned when [write] is asked not to send. */
        data class WouldWrite(val inserts: Int, val deletes: Int) : Outcome

        data class Written(
            val inserts: Int,
            val deletes: Int,
            val version: Long,
            /** What went up, so the caller can record it as what the account now holds. */
            val pushed: List<Pushed>
        ) : Outcome

        /**
         * The account moved under us. The caller re-reads and tries once more; it is not an
         * error, it is how two devices writing at once is meant to resolve.
         */
        data object Conflict : Outcome

        data class Refused(val why: String) : Outcome
    }

    /**
     * @param manifestVersion the version of the manifest the read that just ran was holding.
     * @param manifestIdentifiers every identifier that manifest named, carried across as-is
     *   except where a row explicitly replaces one.
     * @param send false runs everything except the request, so the diff can be inspected
     *   before anything leaves the phone.
     */
    fun write(
        manifestVersion: Long,
        manifestIdentifiers: List<ManifestRecord.Identifier>,
        recordIkm: RecordIkm?,
        desiredFor: (SignalContactStore.Pending) -> Desired?,
        send: Boolean,
        /**
         * Asked with the plaintext records just before the request, and a no stops it. Where
         * the loop guard sits: it fingerprints what would go up, which is only known here.
         */
        mayWrite: (List<ByteArray>) -> Boolean = { true }
    ): Outcome {
        val storageKey = keys.storageKey()
            ?: return Outcome.Refused("this account's storage key is not here")

        val pending = runCatching { contacts.needingStoragePush() }
            .onFailure { Timber.w(it, "signal storage write: could not read what is marked") }
            .getOrNull()
            ?: return Outcome.Refused("could not read what is marked for a push")
        if (pending.isEmpty()) return Outcome.NothingToDo

        val inserts = mutableListOf<StorageItem>()
        // ⚠ ByteStrings, not base64 strings. Identity of a storage id is the sixteen bytes;
        // comparing text means a padding or case difference silently fails to match, and a
        // delete that fails to match leaves the stale record on the account to be re-applied.
        val deleteIds = mutableListOf<okio.ByteString>()
        val insertIdentifiers = mutableListOf<ManifestRecord.Identifier>()
        val pushed = mutableListOf<Pushed>()
        val alreadyThere = mutableListOf<String>()

        pending.forEach { row ->
            val newId = row.storageId ?: return@forEach
            val newIdBytes = runCatching {
                android.util.Base64.decode(newId, android.util.Base64.NO_WRAP)
            }.getOrNull() ?: return@forEach
            // ⚠ Signal's ids are sixteen bytes. A different length is not something to send
            // and hope about: the manifest is validated by every other device on the account.
            if (newIdBytes.size != STORAGE_ID_BYTES) {
                Timber.w("signal storage write: refusing an id of %d bytes", newIdBytes.size)
                return@forEach
            }

            val raw = row.serviceId?.let { contacts.storageRecordFor(it) }
                ?: row.groupId?.let { contacts.storageRecordForGroup(it) }
            if (raw == null) {
                // ⛔ No record from the account means there is nothing to amend, and building
                // one here would be inventing the fields this app cannot see. Skipped rather
                // than guessed at; a row the account has never held is a *creation*, which is
                // a different piece of work with different risks.
                Timber.i("signal storage write: skipping a row the account has no record for")
                return@forEach
            }

            val desired = desiredFor(row) ?: return@forEach
            val amended = runCatching { amend(raw, desired, System.currentTimeMillis()) }
                .onFailure { Timber.w(it, "signal storage write: a record would not re-encode") }
                .getOrNull() ?: return@forEach
            // The account already says this. Sending it again would be a write that changes
            // nothing but the id -- the churn a loop is made of.
            // Compared decoded, not as bytes: another client may encode the same record in a
            // different field order, and that is not a change. Wire's equality includes the
            // fields this build does not understand.
            if (StorageRecord.ADAPTER.decode(amended) == StorageRecord.ADAPTER.decode(raw)) {
                alreadyThere += newId
                return@forEach
            }
            pushed += Pushed(newId, amended)

            val itemKey = recordIkm?.deriveStorageItemKey(newIdBytes)
                ?: storageKey.deriveItemKey(newIdBytes)
            inserts += StorageItem(
                key = newIdBytes.toByteString(),
                value_ = SignalStorageCipher.encrypt(itemKey, amended).toByteString()
            )
            insertIdentifiers += ManifestRecord.Identifier(
                raw = newIdBytes.toByteString(),
                type = if (row.groupId != null) {
                    ManifestRecord.Identifier.Type.GROUPV2
                } else {
                    ManifestRecord.Identifier.Type.CONTACT
                }
            )
            // Only the id this row is replacing, and only when it is a different one.
            row.remoteStorageId
                ?.takeIf { it != newId }
                ?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull() }
                ?.let { deleteIds += it.toByteString() }
        }

        if (inserts.isEmpty()) {
            return if (alreadyThere.isEmpty()) Outcome.NothingToDo else Outcome.AlreadyThere(alreadyThere)
        }

        val deleteSet = deleteIds.toSet()
        val kept = manifestIdentifiers.filterNot { it.raw in deleteSet }
        val identifiers = kept + insertIdentifiers

        // ⚠ Validation before the request, because a malformed manifest damages the account's
        // records for every device rather than only this one. Signal keeps
        // `StorageSyncValidations` for the same reason.
        validate(manifestIdentifiers, identifiers, insertIdentifiers, deleteSet)
            ?.let { return Outcome.Refused(it) }

        if (!send) return Outcome.WouldWrite(inserts.size, deleteSet.size)
        if (!mayWrite(pushed.map { it.record })) return Outcome.Refused("held back by the loop guard")

        val version = manifestVersion + 1
        val manifestRecord = ManifestRecord(
            version = version,
            identifiers = identifiers,
            // Carried, never invented: an account using a record ikm expects every record to
            // be keyed from it, and dropping it would make every record on the account
            // unreadable to the devices that expect one.
            // ⚠ Empty, not null, when the account does not use one: the field is a non-null
            // ByteString in the generated type and an empty one is how "absent" is expressed.
            recordIkm = recordIkm?.value?.toByteString() ?: okio.ByteString.EMPTY
        )
        val manifest = StorageManifest(
            version = version,
            value_ = SignalStorageCipher.encrypt(
                storageKey.deriveManifestKey(version), manifestRecord.encode()
            ).toByteString()
        )

        val api = StorageServiceApi(connection.authenticated, connection.push)
        val auth = api.getAuth().successOrNull()
            ?: return Outcome.Refused("the service would not give an auth token")

        val result = api.writeStorageItems(
            auth,
            WriteOperation(
                manifest = manifest,
                insertItem = inserts,
                deleteKey = deleteIds
            )
        )

        return when {
            result is org.signal.network.NetworkResult.Success ->
                Outcome.Written(inserts.size, deleteSet.size, version, pushed)
            // 409 is the documented "your version is not remoteVersion + 1": somebody else
            // wrote while this was being built. Not an error.
            result is org.signal.network.NetworkResult.StatusCodeError && result.code == 409 ->
                Outcome.Conflict
            else -> Outcome.Refused("the write was refused: $result")
        }
    }

    /**
     * The checks that must pass before anything is sent.
     *
     * Every one of these has the same justification: a bad manifest is not a local bug. It is
     * applied by every other device on the account.
     */
    internal companion object {
        /** Signal's ids are sixteen bytes; `StorageSyncHelper.KEY_GENERATOR` makes them. */
        const val STORAGE_ID_BYTES = 16

        /**
         * What "muted until" to write, given what the account holds.
         *
         * ⚠ This app knows muted or not, and the account knows *until when*. A mute Signal set
         * for eight hours is still muted here, and writing it back as "for ever" -- or a mute
         * that has run out as "off", which it already is -- would be this phone rewriting a
         * decision it never saw. So the account's own value stands whenever it already agrees,
         * and only a real change is written: `Long.MAX_VALUE` for muted, which is how Signal
         * spells "for ever" (`SoundsAndNotificationsSettingsScreen`), and 0 for not.
         */
        internal fun mutedUntilFor(current: Long, muted: Boolean, now: Long): Long = when {
            (current > now) == muted -> current
            muted -> Long.MAX_VALUE
            else -> 0L
        }

        /**
         * The checks that must pass before anything is sent.
         *
         * Every one has the same justification: a bad manifest is not a local bug. It is
         * applied by every other device on the account. Signal keeps `StorageSyncValidations`
         * for exactly this reason.
         */
        internal fun validate(
            before: List<ManifestRecord.Identifier>,
            after: List<ManifestRecord.Identifier>,
            inserted: List<ManifestRecord.Identifier>,
            deleted: Set<okio.ByteString>
        ): String? {
            // The arithmetic has to hold exactly. If it does not, something was dropped that
            // was not meant to be -- the failure this whole class is arranged to prevent.
            val expected = before.size - deleted.size + inserted.size
            if (after.size != expected) {
                return "the manifest would have $expected entries and has ${after.size}"
            }
            val raws = after.mapNotNull { it.raw }
            if (raws.size != after.size) return "the manifest would carry an entry with no id"
            // Two entries with the same id is a manifest no client can resolve.
            if (raws.size != raws.toSet().size) return "the manifest would name an id twice"
            // ⛔ Nothing may leave the manifest except the ids this write explicitly replaces.
            val lost = before.mapNotNull { it.raw }.toSet() - raws.toSet() - deleted
            if (lost.isNotEmpty()) {
                return "the write would drop ${lost.size} record(s) it was not replacing"
            }
            return null
        }

        /**
         * Puts this device's decisions into the record the account already holds.
         *
         * ⚠ Decode, set, re-encode -- never construct. Wire keeps fields it did not recognise
         * on the decoded message, so re-encoding carries them back out untouched. Building a
         * fresh record from the fields below would drop everything a newer client wrote.
         */
        internal fun amend(raw: ByteArray, desired: Desired, now: Long): ByteArray {
            val record = StorageRecord.ADAPTER.decode(raw)
            record.contact?.let { contact ->
                return record.copy(
                    contact = contact.copy(
                        blocked = desired.blocked ?: contact.blocked,
                        mutedUntilTimestamp = mutedUntilFor(contact.mutedUntilTimestamp, desired.muted, now),
                        archived = desired.archived
                    )
                ).encode()
            }
            record.groupV2?.let { group ->
                return record.copy(
                    groupV2 = group.copy(
                        blocked = desired.blocked ?: group.blocked,
                        mutedUntilTimestamp = mutedUntilFor(group.mutedUntilTimestamp, desired.muted, now),
                        archived = desired.archived
                    )
                ).encode()
            }
            throw IllegalArgumentException("that record is neither a contact nor a group")
        }
    }
}
