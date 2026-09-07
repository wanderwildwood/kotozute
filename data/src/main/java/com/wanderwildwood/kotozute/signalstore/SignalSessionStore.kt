package com.wanderwildwood.kotozute.signalstore

import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore

/**
 * The double-ratchet state, one record per peer device.
 *
 * Ported from signal-cli's `SessionStore` (GPL-3.0). This is the most fragile table in the
 * store: a stale record written back over a fresher one is not a crash but **silent ratchet
 * corruption**, and it surfaces later as messages that will not decrypt, pointing nowhere near
 * the cause. Everything below is shaped by that.
 *
 * **No cache, deliberately.** signal-cli keeps loaded records in a map; that is a sound
 * optimisation in a process that owns its own lifecycle, and a liability here. A cached record
 * that outlives a write from another thread is exactly the corruption described above, and
 * SQLite reads under an already-held lock are cheap. If profiling ever says otherwise, cache
 * with a great deal more care than a HashMap.
 */
internal class SignalSessionStore(
    private val db: ProtocolDatabase,
    private val accountIdType: Int
) : SessionStore {

    /**
     * Never null. libsignal expects an empty record for an unknown peer rather than an
     * absence -- returning null here would turn a first message into a crash.
     */
    override fun loadSession(address: SignalProtocolAddress): SessionRecord = withLock {
        load(address) ?: SessionRecord()
    }

    /**
     * All or nothing. If any address lacks a session the whole call fails, because the caller
     * is about to encrypt to a device list and a quietly shorter list would mean a message
     * that silently does not reach someone.
     */
    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        withLock {
            val found = addresses.mapNotNull { load(it) }
            if (found.size != addresses.size) {
                throw NoSessionException(
                    "asked for ${addresses.size} sessions, found ${found.size}"
                )
            }
            found
        }

    /** Every device for this peer **except** the primary, which is what libsignal means by sub. */
    override fun getSubDeviceSessions(name: String): List<Int> = withLock {
        db.readableDatabase.rawQuery(
            "SELECT device_id FROM session WHERE account_id_type = ? AND address = ? AND device_id != ?",
            arrayOf(accountIdType.toString(), name, PRIMARY_DEVICE_ID.toString())
        ).use { c ->
            generateSequence { if (c.moveToNext()) c.getInt(0) else null }.toList()
        }
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) = withLock {
        db.writableDatabase.execSQL(
            """
            INSERT INTO session (account_id_type, address, device_id, record)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(account_id_type, address, device_id) DO UPDATE SET record = excluded.record
            """.trimIndent(),
            arrayOf(accountIdType, address.name, address.deviceId, record.serialize())
        )
    }

    /**
     * A row is not enough: signal-cli asks `hasSenderChain()`, and so does this.
     *
     * A record can exist without one -- archived, or created but never completed -- and
     * treating that as a usable session means encrypting with a ratchet that has no sending
     * side. The row-exists answer looks right in a test and fails in the field.
     */
    override fun containsSession(address: SignalProtocolAddress): Boolean = withLock {
        load(address)?.hasSenderChain() == true
    }

    override fun deleteSession(address: SignalProtocolAddress) = withLock {
        db.writableDatabase.execSQL(
            "DELETE FROM session WHERE account_id_type = ? AND address = ? AND device_id = ?",
            arrayOf(accountIdType, address.name, address.deviceId)
        )
    }

    override fun deleteAllSessions(name: String) = withLock {
        db.writableDatabase.execSQL(
            "DELETE FROM session WHERE account_id_type = ? AND address = ?",
            arrayOf(accountIdType, name)
        )
    }

    private fun load(address: SignalProtocolAddress): SessionRecord? =
        db.readableDatabase.rawQuery(
            "SELECT record FROM session WHERE account_id_type = ? AND address = ? AND device_id = ?",
            arrayOf(accountIdType.toString(), address.name, address.deviceId.toString())
        ).use { c -> if (c.moveToFirst()) SessionRecord(c.getBlob(0)) else null }

    private inline fun <T> withLock(body: () -> T): T {
        db.lock.lock()
        try {
            return body()
        } finally {
            db.lock.unlock()
        }
    }

    companion object {
        /** Signal's primary device is always 1; the linked ones count up from there. */
        const val PRIMARY_DEVICE_ID = 1
    }
}
