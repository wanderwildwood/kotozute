package com.wanderwildwood.kotozute.signalstore

import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.groups.state.SenderKeyStore
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Group sender keys, ported from signal-cli's `SenderKeyRecordStore` (GPL-3.0).
 *
 * Small interface, large consequence: every group message from a modern client arrives as a
 * sender-key message, so a group is unreadable without this — not degraded, unreadable.
 *
 * Unlike the other stores this one is **not** partitioned by account id type, matching
 * signal-cli. A sender key belongs to a (sender, device, distribution) triple, and the local
 * service id does not enter into it.
 */
internal class SignalSenderKeyStore(
    private val db: ProtocolDatabase
) : SenderKeyStore {

    override fun storeSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
        record: SenderKeyRecord
    ) = withLock {
        db.writableDatabase.execSQL(
            """
            INSERT INTO sender_key (address, device_id, distribution_id, record, created_timestamp)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(address, device_id, distribution_id) DO UPDATE SET
              record = excluded.record, created_timestamp = excluded.created_timestamp
            """.trimIndent(),
            arrayOf(
                sender.name, sender.deviceId, distributionId.toBytes(),
                record.serialize(), System.currentTimeMillis()
            )
        )
    }

    /**
     * Null for an unknown key, which libsignal expects here — unlike the session store, where
     * an absence has to become an empty record. The two look alike and are not.
     */
    override fun loadSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID
    ): SenderKeyRecord? = withLock {
        db.readableDatabase.rawQuery(
            "SELECT record FROM sender_key WHERE address = ? AND device_id = ? AND distribution_id = ?",
            arrayOf(sender.name, sender.deviceId.toString(), distributionId.toBytes())
        ).use { c -> if (c.moveToFirst()) SenderKeyRecord(c.getBlob(0)) else null }
    }

    /**
     * Stored as 16 raw bytes, big-endian, as signal-cli's `UuidUtil.toByteArray` does.
     *
     * Not as text. The column is a BLOB and the two representations are not interchangeable:
     * a UUID written as a string would simply never match a lookup, and the symptom would be
     * group messages that fail to decrypt rather than an error pointing here.
     */
    private fun UUID.toBytes(): ByteArray =
        ByteBuffer.allocate(16)
            .putLong(mostSignificantBits)
            .putLong(leastSignificantBits)
            .array()

    private inline fun <T> withLock(body: () -> T): T {
        db.lock.lock()
        try {
            return body()
        } finally {
            db.lock.unlock()
        }
    }
}
