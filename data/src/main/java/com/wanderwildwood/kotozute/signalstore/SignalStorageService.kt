package com.wanderwildwood.kotozute.signalstore

import org.signal.core.models.storageservice.StorageKey
import org.whispersystems.signalservice.api.storage.RecordIkm
import org.whispersystems.signalservice.api.storage.SignalStorageCipher
import org.whispersystems.signalservice.api.storage.StorageServiceApi
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

    /** What a read did, for a status line to say and a log to carry. */
    data class Result(val contacts: Int, val records: Int, val reason: String? = null)

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
        // In batches: a manifest can name thousands of records, and the service takes a list
        // of ids per request rather than all of them.
        wanted.chunked(BATCH).forEach { batch ->
            val items = api.readStorageItems(auth, ReadOperation(readKey = batch)).successOrNull()
                ?: return@forEach
            val found = items.items.mapNotNull { item ->
                val id = item.key.toByteArray()
                val itemKey = ikm?.deriveStorageItemKey(id) ?: storageKey.deriveItemKey(id)
                runCatching {
                    StorageRecord.ADAPTER.decode(
                        SignalStorageCipher.decrypt(itemKey, item.value_.toByteArray())
                    ).contact
                }.getOrNull()
            }
            seen += items.items.size
            val people = found.mapNotNull { record ->
                val aci = record.aci?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SignalContactStore.Contact(
                    aci = aci,
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
        Timber.i("signal storage: %d contact(s) from %d record(s)", kept, seen)
        return Result(kept, seen, null)
    }

    companion object {
        private const val BATCH = 200

        /**
         * What to call somebody. The name from the reader's own address book first, as
         * everywhere else in this app: it is what they call this person, where the profile
         * name is what the person calls themselves.
         */
        fun nameOf(record: org.whispersystems.signalservice.internal.storage.protos.ContactRecord): String? {
            val system = listOf(record.systemGivenName, record.systemFamilyName)
                .joinToString(" ") { it.orEmpty() }.trim()
            if (system.isNotBlank()) return system
            val profile = listOf(record.givenName, record.familyName)
                .joinToString(" ") { it.orEmpty() }.trim()
            return profile.takeIf { it.isNotBlank() }
        }
    }
}
