package com.wanderwildwood.kotozute.signalstore

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
 * Reading the account's contact list out of Signal's storage service.
 *
 * This exists because of one report from somebody who is not David: the new-message list
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
    private val contacts: SignalContactStore
) {

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
        /**
         * Of [anonymous], those carrying a phone number.
         *
         * The one fact that decides what can be done about them: a number is what contact
         * discovery takes, so these are recoverable. One with neither an account id nor a
         * number is not addressable by anything this app could ask for.
         */
        val anonymousWithNumber: Int = 0
    )

    fun read(): Result {
        val storageKey = keys.storageKey()
            ?: return Result(0, 0, "the storage key is not here yet")

        connection.connect()
        val api = StorageServiceApi(connection.authenticated, connection.push)

        val auth = api.getAuth().successOrNull()
            ?: return Result(0, 0, "the service would not give an auth token")

        val manifest = api.getStorageManifest(auth).successOrNull()
            ?: return Result(0, 0, "the manifest could not be read")

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

        val wanted = manifestRecord.identifiers
            .filter { it.type == ManifestRecord.Identifier.Type.CONTACT }
            .mapNotNull { it.raw }
        if (wanted.isEmpty()) return Result(0, 0, null)

        var kept = 0
        var seen = 0
        var unopened = 0
        var notContacts = 0
        var anonymous = 0
        var pniOnly = 0
        var anonymousWithNumber = 0
        // In batches: a manifest can name thousands of records, and the service takes a list
        // of ids per request rather than all of them.
        wanted.chunked(BATCH).forEach { batch ->
            val items = api.readStorageItems(auth, ReadOperation(readKey = batch)).successOrNull()
                ?: return@forEach
            seen += items.items.size
            val found = items.items.mapNotNull { item ->
                val id = item.key.toByteArray()
                val itemKey = ikm?.deriveStorageItemKey(id) ?: storageKey.deriveItemKey(id)
                val record = runCatching {
                    StorageRecord.ADAPTER.decode(
                        SignalStorageCipher.decrypt(itemKey, item.value_.toByteArray())
                    )
                }.getOrElse { unopened++; null } ?: return@mapNotNull null
                record.contact ?: run { notContacts++; null }
            }
            val people = found.mapNotNull { record ->
                val aci = aciOf(record)
                val pni = pniOf(record)

                // A record carrying both is the account telling this phone, on its own
                // authority, that these two ids are one person. It is the only pairing that
                // is trusted here -- see the note on ProtocolStoreSchema.PNI_ACI -- and it is
                // what later folds a conversation held under the PNI into the real one.
                if (aci != null && pni != null) {
                    runCatching { contacts.pair(pni, aci) }
                        .onFailure { Timber.w(it, "signal storage: a pairing would not keep") }
                }

                // The account id where there is one, the phone-number identity otherwise.
                // Somebody who has turned off "who can find me by number" is known to Signal
                // by their PNI and nothing else, and that is a real address -- messages sent
                // to it arrive. Dropping them was why two thirds of one account's contacts
                // could not be written to.
                val id = aci ?: pni
                if (id == null) {
                    anonymous++
                    if (!record.e164.isNullOrBlank()) anonymousWithNumber++
                    return@mapNotNull null
                }
                if (aci == null) pniOnly++
                SignalContactStore.Contact(
                    aci = id,
                    e164 = record.e164?.takeIf { it.isNotBlank() },
                    name = nameOf(record),
                    profileKey = record.profileKey?.takeIf { it.size > 0 }?.toByteArray()
                )
            }
            if (people.isNotEmpty()) {
                contacts.store(people)
                kept += people.size
            }
        }
        Timber.i(
            "signal storage: %d contact(s) from %d record(s); dropped %d unopened, %d not contacts, %d anonymous (%d pni-only, %d with a number)",
            kept, seen, unopened, notContacts, anonymous, pniOnly, anonymousWithNumber
        )
        return Result(kept, seen, null, unopened, notContacts, anonymous, pniOnly, anonymousWithNumber)
    }

    companion object {
        private const val BATCH = 200

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
         * What to call somebody. The name from the reader's own address book first, as
         * everywhere else in this app: it is what they call this person, where the profile
         * name is what the person calls themselves.
         */
        fun nameOf(record: ContactRecord): String? {
            val system = listOf(record.systemGivenName, record.systemFamilyName)
                .joinToString(" ") { it.orEmpty() }.trim()
            if (system.isNotBlank()) return system
            val profile = listOf(record.givenName, record.familyName)
                .joinToString(" ") { it.orEmpty() }.trim()
            return profile.takeIf { it.isNotBlank() }
        }
    }
}
