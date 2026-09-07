package com.wanderwildwood.kotozute.signalstore

import android.content.Context
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.state.IdentityKeyStore
import java.security.SecureRandom

/**
 * EXPERIMENT (signal-on-the-phone branch). Proves the protocol database creates and opens.
 *
 * Lives here rather than in the presentation layer because SQLCipher is an implementation
 * detail of this module and should stay one -- the app above has no business holding a
 * `SQLiteOpenHelper`. This hands back a sentence instead.
 *
 * Throwaway key and throwaway file: this asks whether the schema is well-formed and whether
 * the encrypted database opens at all, which is worth knowing long before linking depends on
 * it. It is not a test of the real store's lifecycle.
 */
object ProtocolDatabaseSelfCheck {

    fun describe(context: Context): String {
        val file = context.getDatabasePath("selfcheck-${System.nanoTime()}.db")
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return try {
            val db = ProtocolDatabase(context.withDatabaseName(file.name), key)
            val tables = db.readableDatabase.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                null
            ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
            val identities = db.readableDatabase.rawQuery(
                "SELECT count(*) FROM account_identity", null
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }
            // Exercise the identity store's trust policy, which is the part that is easy to
            // get subtly wrong and impossible to notice until someone's safety number changes.
            val store = SignalIdentityKeyStore(db, ProtocolDatabase.ACCOUNT_ID_TYPE_ACI)
            val peer = org.signal.libsignal.protocol.SignalProtocolAddress("+15550001111", 1)
            val first = IdentityKeyPair.generate().publicKey
            val second = IdentityKeyPair.generate().publicKey

            val trustedOnFirstSighting = store.isTrustedIdentity(peer, first, IdentityKeyStore.Direction.RECEIVING)
            val changedKeyRefusedOnReceive = !store.isTrustedIdentity(peer, second, IdentityKeyStore.Direction.RECEIVING)
            val changedKeyBlockedOnSend = !store.isTrustedIdentity(peer, second, IdentityKeyStore.Direction.SENDING)
            val readBack = store.getIdentity(peer) != null
            val changeReported =
                store.saveIdentity(peer, first) == IdentityKeyStore.IdentityChange.REPLACED_EXISTING

            db.close()
            "${tables.size} tables, seeded=$identities | trust: first-sighting=$trustedOnFirstSighting " +
                "changed-refused-on-receive=$changedKeyRefusedOnReceive " +
                "changed-blocked-on-send=$changedKeyBlockedOnSend " +
                "readback=$readBack change-reported=$changeReported"
        } finally {
            file.delete()
        }
    }

    /** The helper names its own file, so point it at a throwaway one. */
    private fun Context.withDatabaseName(name: String): Context =
        object : android.content.ContextWrapper(this) {
            override fun getDatabasePath(unused: String) = super.getDatabasePath(name)
        }
}
