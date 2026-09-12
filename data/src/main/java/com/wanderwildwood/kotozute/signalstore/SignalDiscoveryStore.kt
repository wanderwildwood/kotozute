package com.wanderwildwood.kotozute.signalstore

/**
 * What contact discovery has already been told.
 *
 * CDSI charges an account's quota for the numbers it has *not* been asked about before. A run
 * submits three things -- the numbers asked about last time, the ones new since, and the token
 * the service handed back -- and pays only for the difference. Lose any of them and every run
 * is a first run, which is how an account spends its discovery quota and gets refused for the
 * rest of the day.
 *
 * So this is not a cache of results. Results are people, and they live in [SignalContactStore].
 * This is only the record of what has been *asked*, which is the thing the service prices.
 */
internal class SignalDiscoveryStore(private val db: ProtocolDatabase) {

    /** The token from the last successful run, or null before there has been one. */
    fun token(): ByteArray? = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            "SELECT token FROM cds_state WHERE _id = 1", null
        ).use { c -> if (c.moveToFirst()) c.getBlob(0)?.takeIf { it.isNotEmpty() } else null }
    }

    /**
     * Keeps the token the service just issued.
     *
     * Written on its own, and before the results are stored: the service has already counted
     * the run by the time it hands this over, so losing it costs quota even though nothing
     * else about the run succeeded.
     */
    fun keepToken(token: ByteArray) = withStoreLock(db) {
        db.writableDatabase.execSQL(
            """
            INSERT INTO cds_state (_id, token, updated_timestamp)
            VALUES (1, ?, ?)
            ON CONFLICT(_id) DO UPDATE SET
              token = excluded.token,
              updated_timestamp = excluded.updated_timestamp
            """.trimIndent(),
            arrayOf<Any?>(token, System.currentTimeMillis())
        )
    }

    /** Every number already submitted. */
    fun submitted(): Set<String> = withStoreLock(db) {
        db.readableDatabase.rawQuery("SELECT e164 FROM cds_submitted", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet()
        }
    }

    /**
     * Records numbers as asked about.
     *
     * Called with everything submitted, not only what came back with somebody on the other
     * end: a number that is on nobody's account is still a number this account has spent its
     * quota asking about, and asking again next week costs the same as the first time.
     */
    fun remember(e164s: Set<String>) = withStoreLock(db) {
        if (e164s.isEmpty()) return@withStoreLock
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            e164s.forEach { number ->
                database.execSQL(
                    "INSERT INTO cds_submitted (e164) VALUES (?) ON CONFLICT(e164) DO NOTHING",
                    arrayOf<Any?>(number)
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /** Forgets everything asked, so the next run is a first run. For a deliberate re-ask only. */
    fun forget() = withStoreLock(db) {
        db.writableDatabase.execSQL("DELETE FROM cds_submitted")
        db.writableDatabase.execSQL("DELETE FROM cds_state")
    }
}
