package com.wanderwildwood.kotozute.signalstore

import org.signal.libsignal.protocol.ServiceId
import java.util.Optional

/**
 * Where key transparency keeps what it has already verified.
 *
 * `org.signal.libsignal.keytrans.Store`, which libsignal reads and writes during a check and
 * nothing else in this app touches. Two pieces: the account-wide **distinguished tree head**,
 * the last point in Signal's public log this device checked against, and per person the
 * **account data** proving their identifiers were in the log when we last looked.
 *
 * Ported from upstream's `KeyTransparencyStore`
 * (`app/src/main/java/org/thoughtcrime/securesms/database/model/KeyTransparencyStore.kt`), which
 * is the same four methods over the same two places — the account record and the recipient row.
 *
 * ⚠ **Both values are opaque and stay that way.** Nothing here parses them, compares them, or
 * decides anything from them. The point of key transparency is that libsignal checks the log's
 * own proofs; a client that formed its own opinion about these bytes would be a client that can
 * be argued into accepting a substituted identity key, which is the thing being defended
 * against.
 *
 * ⚠ **Writes must not throw.** libsignal calls these from inside a native check, and an
 * exception crossing that boundary aborts a security check with an error that names a database
 * rather than a verification. A failed write means the next check starts further back and does
 * more work, which is the cheap failure; it is logged and swallowed for that reason, and is one
 * of the few places in this rail where swallowing is right.
 */
internal class SignalKeyTransparencyStore(
    private val accounts: SignalAccountStore,
    private val contacts: SignalContactStore
) : org.signal.libsignal.keytrans.Store {

    override fun getLastDistinguishedTreeHead(): Optional<ByteArray> =
        Optional.ofNullable(runCatching { accounts.distinguishedHead() }.getOrNull())

    override fun setLastDistinguishedTreeHead(lastDistinguishedTreeHead: ByteArray) {
        runCatching { accounts.saveDistinguishedHead(lastDistinguishedTreeHead) }
            .onFailure {
                // ⚠ Must not throw: libsignal calls this from inside its own verification and
                // an exception here fails the check rather than the write. A head that did not
                // keep costs the next check a fresh fetch of the distinguished tree, which is
                // the ordinary cold-start cost, not a wrong answer.
                timber.log.Timber.w(it, "signal kt: could not keep the distinguished head; the next check refetches")
            }
    }

    override fun getAccountData(aci: ServiceId.Aci): Optional<ByteArray> =
        Optional.ofNullable(
            runCatching { contacts.keyTransparencyDataFor(aci.toServiceIdString()) }.getOrNull()
        )

    override fun setAccountData(aci: ServiceId.Aci, data: ByteArray) {
        runCatching { contacts.saveKeyTransparencyData(aci.toServiceIdString(), data) }
            .onFailure {
                // Same contract: never throw back into libsignal. Losing this makes the next
                // check treat the account as unseen and verify it from scratch -- slower, and
                // it cannot turn a failed verification into a passed one.
                timber.log.Timber.w(it, "signal kt: could not keep what was verified; the next check starts fresh")
            }
    }
}
