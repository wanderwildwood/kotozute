package com.wanderwildwood.kotozute.signalstore

import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair
import org.whispersystems.signalservice.internal.crypto.PrimaryProvisioningCipher
import org.whispersystems.signalservice.internal.crypto.SecondaryProvisioningCipher
import org.whispersystems.signalservice.internal.push.ProvisionEnvelope
import org.whispersystems.signalservice.internal.push.ProvisionMessage
import org.whispersystems.signalservice.internal.push.ProvisioningVersion

/**
 * The salt survives provisioning, and its absence is absence rather than zero bytes.
 *
 * Linking is the least-tested thing this app does. Upstream has no test for
 * `ProvisioningSocket` at all, and its one provisioning test,
 * `SecondaryProvisioningCipherTest`, round-trips a message that **never sets
 * `authCredentialSalt`** -- field 19, the one this app started depending on at schema v32.
 *
 * So nothing anywhere proved that the field this device now stores at link time actually
 * arrives. Re-linking a phone to find out costs that device its session history, which is not a
 * price worth paying for a question a cipher round-trip answers offline.
 *
 * Built the way upstream builds its own: a real `PrimaryProvisioningCipher` encrypting to a real
 * `SecondaryProvisioningCipher`'s public key, decrypted back. No mocks -- the point is that the
 * bytes survive the actual encoding.
 */
class ProvisioningSaltTest {

    /** The salt is sixteen bytes; `Provisioning.proto` says so at field 19. */
    private val salt = ByteArray(16) { (it + 1).toByte() }

    private fun roundTrip(authCredentialSalt: ByteString?): ProvisionMessage {
        val secondary = SecondaryProvisioningCipher.generate(IdentityKeyPair.generate())
        val primary = PrimaryProvisioningCipher(secondary.secondaryDevicePublicKey.publicKey)
        val primaryIdentity = IdentityKeyPair.generate()

        val sent = ProvisionMessage(
            aciIdentityKeyPublic = ByteString.of(*primaryIdentity.publicKey.serialize()),
            aciIdentityKeyPrivate = ByteString.of(*primaryIdentity.privateKey.serialize()),
            provisioningCode = "code",
            provisioningVersion = ProvisioningVersion.CURRENT.value,
            number = "+14045555555",
            authCredentialSalt = authCredentialSalt
        )

        val envelope = ProvisionEnvelope.ADAPTER.decode(primary.encrypt(sent))
        val result = secondary.decrypt(envelope)
        return (result as SecondaryProvisioningCipher.ProvisioningDecryptResult.Success).message
    }

    @Test
    fun `a salt sent by the primary arrives byte for byte`() {
        val received = roundTrip(salt.toByteString())
        assertArrayEquals(salt, received.authCredentialSaltOrNull())
    }

    @Test
    fun `a primary that sends no salt leaves this device with none`() {
        assertNull(roundTrip(null).authCredentialSaltOrNull())
    }

    /**
     * The case both Kompakts are in is not this one -- they were linked before v32 and have no
     * salt at all -- but a primary *setting* the field to nothing is the shape that would put a
     * zero-length salt in the database. `receiveAuthCredentialWithoutPni` wants sixteen bytes,
     * and the failure would surface as every group refusing to authorize, nowhere near linking.
     */
    @Test
    fun `an empty salt is treated as no salt, not as zero bytes`() {
        assertNull(roundTrip(ByteString.EMPTY).authCredentialSaltOrNull())
    }
}
