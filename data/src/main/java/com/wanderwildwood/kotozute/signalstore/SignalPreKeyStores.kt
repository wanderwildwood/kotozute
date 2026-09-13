package com.wanderwildwood.kotozute.signalstore

import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import timber.log.Timber

/**
 * The three pre-key stores, ported from signal-cli (GPL-3.0).
 *
 * They keep the same shape as signal-cli's tables, including the choice to split the EC keys
 * into public and private columns while storing the Kyber record as one serialized blob. That
 * asymmetry is signal-cli's, not an accident here, and keeping it means its SQL and this SQL
 * still read as the same thing when something needs comparing.
 *
 * Missing keys throw [InvalidKeyIdException] rather than returning null. libsignal treats that
 * as an ordinary outcome -- a peer asking for a pre key that has already been consumed -- so
 * the exception is the protocol working, not a fault.
 */

internal class SignalPreKeyStore(
    private val db: ProtocolDatabase,
    private val accountIdType: Int
) : PreKeyStore {

    override fun loadPreKey(preKeyId: Int): PreKeyRecord = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            "SELECT public_key, private_key FROM pre_key WHERE account_id_type = ? AND key_id = ?",
            arrayOf(accountIdType.toString(), preKeyId.toString())
        ).use { c ->
            if (!c.moveToFirst()) throw InvalidKeyIdException("no pre key $preKeyId")
            PreKeyRecord(preKeyId, ECKeyPair(ECPublicKey(c.getBlob(0)), ECPrivateKey(c.getBlob(1))))
        }
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) = withStoreLock(db) {
        db.writableDatabase.execSQL(
            """
            INSERT INTO pre_key (account_id_type, key_id, public_key, private_key)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(account_id_type, key_id) DO UPDATE SET
              public_key = excluded.public_key, private_key = excluded.private_key
            """.trimIndent(),
            arrayOf(
                accountIdType, preKeyId,
                record.keyPair.publicKey.serialize(), record.keyPair.privateKey.serialize()
            )
        )
    }

    override fun containsPreKey(preKeyId: Int): Boolean = withStoreLock(db) {
        db.exists("pre_key", accountIdType, preKeyId)
    }

    /**
     * Called once the key has been used. One-time keys are exactly that: leaving a consumed
     * key in the table would let it be handed out again, which is the one thing a one-time
     * key must never be.
     */
    override fun removePreKey(preKeyId: Int) = withStoreLock(db) {
        db.writableDatabase.execSQL(
            "DELETE FROM pre_key WHERE account_id_type = ? AND key_id = ?",
            arrayOf(accountIdType, preKeyId)
        )
    }
}

internal class SignalSignedPreKeyStore(
    private val db: ProtocolDatabase,
    private val accountIdType: Int
) : SignedPreKeyStore {

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            "SELECT public_key, private_key, signature, timestamp FROM signed_pre_key WHERE account_id_type = ? AND key_id = ?",
            arrayOf(accountIdType.toString(), signedPreKeyId.toString())
        ).use { c ->
            if (!c.moveToFirst()) throw InvalidKeyIdException("no signed pre key $signedPreKeyId")
            SignedPreKeyRecord(
                signedPreKeyId,
                c.getLong(3),
                ECKeyPair(ECPublicKey(c.getBlob(0)), ECPrivateKey(c.getBlob(1))),
                c.getBlob(2)
            )
        }
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            "SELECT key_id, public_key, private_key, signature, timestamp FROM signed_pre_key WHERE account_id_type = ?",
            arrayOf(accountIdType.toString())
        ).use { c ->
            generateSequence {
                if (c.moveToNext()) SignedPreKeyRecord(
                    c.getInt(0),
                    c.getLong(4),
                    ECKeyPair(ECPublicKey(c.getBlob(1)), ECPrivateKey(c.getBlob(2))),
                    c.getBlob(3)
                ) else null
            }.toList()
        }
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) =
        withStoreLock(db) {
            db.writableDatabase.execSQL(
                """
                INSERT INTO signed_pre_key (account_id_type, key_id, public_key, private_key, signature, timestamp)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(account_id_type, key_id) DO UPDATE SET
                  public_key = excluded.public_key, private_key = excluded.private_key,
                  signature = excluded.signature, timestamp = excluded.timestamp
                """.trimIndent(),
                arrayOf(
                    accountIdType, signedPreKeyId,
                    record.keyPair.publicKey.serialize(), record.keyPair.privateKey.serialize(),
                    record.signature,
                    // The timestamp is not decoration: it is what rotation is measured from.
                    record.timestamp
                )
            )
        }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = withStoreLock(db) {
        db.exists("signed_pre_key", accountIdType, signedPreKeyId)
    }

    override fun removeSignedPreKey(signedPreKeyId: Int) = withStoreLock(db) {
        db.writableDatabase.execSQL(
            "DELETE FROM signed_pre_key WHERE account_id_type = ? AND key_id = ?",
            arrayOf(accountIdType, signedPreKeyId)
        )
    }
}

internal class SignalKyberPreKeyStore(
    private val db: ProtocolDatabase,
    private val accountIdType: Int
) : KyberPreKeyStore {

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            "SELECT serialized FROM kyber_pre_key WHERE account_id_type = ? AND key_id = ?",
            arrayOf(accountIdType.toString(), kyberPreKeyId.toString())
        ).use { c ->
            if (!c.moveToFirst()) throw InvalidKeyIdException("no kyber pre key $kyberPreKeyId")
            KyberPreKeyRecord(c.getBlob(0))
        }
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            "SELECT serialized FROM kyber_pre_key WHERE account_id_type = ?",
            arrayOf(accountIdType.toString())
        ).use { c ->
            generateSequence { if (c.moveToNext()) KyberPreKeyRecord(c.getBlob(0)) else null }.toList()
        }
    }

    /** Stores a one-time key. Last-resort keys go through [storeLastResortKyberPreKey]. */
    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) =
        store(kyberPreKeyId, record, lastResort = false)

    fun storeLastResortKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) =
        store(kyberPreKeyId, record, lastResort = true)

    private fun store(kyberPreKeyId: Int, record: KyberPreKeyRecord, lastResort: Boolean) =
        withStoreLock(db) {
            db.writableDatabase.execSQL(
                """
                INSERT INTO kyber_pre_key (account_id_type, key_id, serialized, is_last_resort, timestamp)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(account_id_type, key_id) DO UPDATE SET
                  serialized = excluded.serialized, is_last_resort = excluded.is_last_resort,
                  timestamp = excluded.timestamp
                """.trimIndent(),
                arrayOf(
                    accountIdType, kyberPreKeyId, record.serialize(),
                    if (lastResort) 1 else 0, record.timestamp
                )
            )
        }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = withStoreLock(db) {
        db.exists("kyber_pre_key", accountIdType, kyberPreKeyId)
    }

    /**
     * Consumes a one-time Kyber key, or records the use of a last-resort one.
     *
     * Two different jobs, and which one applies depends on the key. A one-time key is deleted
     * the moment it is used, and that deletion is what stops the message that used it from
     * being replayed. A last-resort key is deliberately **not** deleted -- it is the fallback
     * when the one-time keys have run out, and deleting it on first use would leave the account
     * with nothing to fall back to.
     *
     * ⚠ Which left the last-resort key with no replay protection at all, and the comment that
     * used to be here said upstream had none either. It has: `LastResortKeyTupleTable`, a
     * complete implementation, reached from `handleMarkKyberPreKeyUsed`'s else-branch. The
     * claim was wrong and it was the kind of wrong that stops the next reader looking.
     *
     * So a use of a last-resort key is now written down as the triple libsignal hands over --
     * which key, which signed pre key, and the sender's base key -- against a UNIQUE
     * constraint. Seeing it twice is a replay of a `PreKeySignalMessage`, and that is what
     * [ReusedBaseKeyException] exists to tell libsignal, which asks for it here and until now
     * was always told everything was fine.
     *
     * @throws ReusedBaseKeyException when this exact use has been seen before.
     */
    override fun markKyberPreKeyUsed(
        kyberPreKeyId: Int,
        signedPreKeyId: Int,
        baseKey: ECPublicKey
    ) = withStoreLock(db) {
        // One transaction, because the two halves are one decision: which branch applies is
        // read from the same table the branch then writes to.
        db.writableDatabase.beginTransaction()
        try {
            val lastResortRowId = db.writableDatabase.rawQuery(
                "SELECT _id FROM kyber_pre_key " +
                    "WHERE account_id_type = ? AND key_id = ? AND is_last_resort = 1",
                arrayOf(accountIdType.toString(), kyberPreKeyId.toString())
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

            if (lastResortRowId == null) {
                db.writableDatabase.execSQL(
                    "DELETE FROM kyber_pre_key " +
                        "WHERE account_id_type = ? AND key_id = ? AND is_last_resort = 0",
                    arrayOf(accountIdType, kyberPreKeyId)
                )
            } else {
                val serialized = baseKey.serialize()

                // ⚠ Asked, rather than inferred from a failed insert.
                //
                // Upstream catches the UNIQUE violation and converts it. That relies on
                // knowing which exception the driver raises, and this app is on SQLCipher
                // rather than the framework's SQLite -- guessing wrong would turn a detected
                // replay back into a silent success, which is the exact failure being fixed.
                // The constraint is still there and still the backstop; this is simply the
                // half that does not depend on an exception type. It is safe to ask first
                // because the whole thing is one transaction.
                // ⚠ Compared as hex, not as a blob.
                //
                // `rawQuery` binds every argument as a string: a ByteArray handed to it
                // arrives as that array's `toString()`, so `public_key = ?` would never match
                // anything and every replay would be waved through. `execSQL` binds a blob
                // properly, which is why the INSERT below can pass the bytes as they are.
                // SQLite's own `hex()` gives both sides the same uppercase spelling.
                val seen = db.writableDatabase.rawQuery(
                    "SELECT 1 FROM last_resort_key_tuple " +
                        "WHERE kyber_prekey_id = ? AND signed_key_id = ? AND hex(public_key) = ?",
                    arrayOf(
                        lastResortRowId.toString(),
                        signedPreKeyId.toString(),
                        serialized.joinToString("") { "%02X".format(it) }
                    )
                ).use { c -> c.moveToFirst() }

                if (seen) {
                    Timber.w("signal keys: a last-resort key set has been used twice; refusing it")
                    throw ReusedBaseKeyException(
                        "this last-resort key, signed pre key and base key have been used together before"
                    )
                }

                db.writableDatabase.execSQL(
                    "INSERT INTO last_resort_key_tuple " +
                        "(kyber_prekey_id, signed_key_id, public_key) VALUES (?, ?, ?)",
                    arrayOf<Any?>(lastResortRowId, signedPreKeyId, serialized)
                )
            }
            db.writableDatabase.setTransactionSuccessful()
        } finally {
            db.writableDatabase.endTransaction()
        }
    }
}

private fun ProtocolDatabase.exists(table: String, accountIdType: Int, keyId: Int): Boolean =
    readableDatabase.rawQuery(
        "SELECT 1 FROM $table WHERE account_id_type = ? AND key_id = ?",
        arrayOf(accountIdType.toString(), keyId.toString())
    ).use { it.moveToFirst() }
