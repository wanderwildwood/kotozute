package com.wanderwildwood.kotozute.signalstore

import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/**
 * Key generation, following signal-cli's `KeyUtils`.
 *
 * A signed pre key is only meaningful if the signature is over the right bytes and made with
 * the account's identity key -- that is the whole point of "signed" -- so the generation lives
 * in one place rather than being open-coded wherever a key is needed.
 */
internal object KeyUtilsForCheck {

    fun signedPreKey(id: Int, identityPrivate: ECPrivateKey): SignedPreKeyRecord {
        val pair = ECKeyPair.generate()
        val signature = identityPrivate.calculateSignature(pair.publicKey.serialize())
        return SignedPreKeyRecord(id, System.currentTimeMillis(), pair, signature)
    }

    fun kyberPreKey(id: Int, identityPrivate: ECPrivateKey): KyberPreKeyRecord {
        val pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val signature = identityPrivate.calculateSignature(pair.publicKey.serialize())
        return KyberPreKeyRecord(id, System.currentTimeMillis(), pair, signature)
    }
}
