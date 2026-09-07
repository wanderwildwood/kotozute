package com.wanderwildwood.kotozute.signalstore

import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.groups.state.SenderKeyStore
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
    ) = withStoreLock(db) {
        db.writableDatabase.execSQL(
            """
            INSERT INTO sender_key (address, device_id, distribution_id, record, created_timestamp)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(address, device_id, distribution_id) DO UPDATE SET
              record = excluded.record, created_timestamp = excluded.created_timestamp
            """.trimIndent(),
            arrayOf(
                sender.name, sender.deviceId, distributionId.toByteArray(),
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
    ): SenderKeyRecord? = withStoreLock(db) {
        // The distribution id goes in as a literal, not a bound argument -- rawQuery would
        // stringify the byte array and match nothing. See [toSqlBlobLiteral].
        db.readableDatabase.rawQuery(
            "SELECT record FROM sender_key WHERE address = ? AND device_id = ? " +
                "AND distribution_id = ${distributionId.toSqlBlobLiteral()}",
            arrayOf(sender.name, sender.deviceId.toString())
        ).use { c -> if (c.moveToFirst()) SenderKeyRecord(c.getBlob(0)) else null }
    }

}
