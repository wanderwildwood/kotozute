package com.wanderwildwood.kotozute.signalstore

import android.content.Context
import org.signal.core.models.ServiceId
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
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

            // Sessions. The behaviours checked are the ones that are wrong-but-plausible:
            // an unknown peer must yield an empty record rather than null, containsSession
            // must ask about the sender chain rather than the row, an incomplete device list
            // must fail loudly, and the primary must not appear among the sub-devices.
            val sessions = SignalSessionStore(db, ProtocolDatabase.ACCOUNT_ID_TYPE_ACI)
            val a1 = org.signal.libsignal.protocol.SignalProtocolAddress("+15550002222", 1)
            val a2 = org.signal.libsignal.protocol.SignalProtocolAddress("+15550002222", 2)

            val unknownIsEmptyNotNull = sessions.loadSession(a1).let { !it.hasSenderChain() }
            sessions.storeSession(a1, SessionRecord())
            sessions.storeSession(a2, SessionRecord())
            val rowExistsButNotUsable = !sessions.containsSession(a1)
            val subDevicesExcludePrimary = sessions.getSubDeviceSessions("+15550002222") == listOf(2)
            val missingSessionThrows = try {
                sessions.loadExistingSessions(
                    listOf(a1, org.signal.libsignal.protocol.SignalProtocolAddress("+15550009999", 1))
                ); false
            } catch (e: org.signal.libsignal.protocol.NoSessionException) { true }
            sessions.deleteAllSessions("+15550002222")
            val deletedAll = sessions.getSubDeviceSessions("+15550002222").isEmpty()

            // Pre keys. The behaviour that matters is the asymmetry between a one-time key
            // and a last-resort one: consuming the first must delete it, consuming the second
            // must not, or the account loses the fallback it exists to have.
            val aci = ProtocolDatabase.ACCOUNT_ID_TYPE_ACI
            val preKeys = SignalPreKeyStore(db, aci)
            val signed = SignalSignedPreKeyStore(db, aci)
            val kyber = SignalKyberPreKeyStore(db, aci)
            val idKeys = IdentityKeyPair.generate()

            preKeys.storePreKey(7, PreKeyRecord(7, ECKeyPair.generate()))
            val preKeyRoundTrips = preKeys.loadPreKey(7).id == 7
            preKeys.removePreKey(7)
            val oneTimeConsumed = !preKeys.containsPreKey(7)
            val missingPreKeyThrows =
                try { preKeys.loadPreKey(999); false } catch (e: InvalidKeyIdException) { true }

            val sp = KeyUtilsForCheck.signedPreKey(11, idKeys.privateKey)
            signed.storeSignedPreKey(11, sp)
            val signedKeepsTimestamp = signed.loadSignedPreKey(11).timestamp == sp.timestamp

            val oneTime = KeyUtilsForCheck.kyberPreKey(21, idKeys.privateKey)
            val lastResort = KeyUtilsForCheck.kyberPreKey(22, idKeys.privateKey)
            kyber.storeKyberPreKey(21, oneTime)
            kyber.storeLastResortKyberPreKey(22, lastResort)
            kyber.markKyberPreKeyUsed(21, 11, idKeys.publicKey.publicKey)
            kyber.markKyberPreKeyUsed(22, 11, idKeys.publicKey.publicKey)
            val kyberOneTimeGone = !kyber.containsKyberPreKey(21)
            val lastResortSurvives = kyber.containsKyberPreKey(22)

            // Sender keys. The behaviour to pin is the UUID round trip: the column is a BLOB
            // and a UUID written as text would never match a lookup, which would surface as
            // group messages that will not decrypt rather than as an error pointing here.
            val senderKeys = SignalSenderKeyStore(db)
            val groupSender = org.signal.libsignal.protocol.SignalProtocolAddress("+15550003333", 1)
            val distributionId = java.util.UUID.randomUUID()
            val unknownSenderKeyIsNull = senderKeys.loadSenderKey(groupSender, distributionId) == null
            // And a stored one must come back. Worth stating separately because the not-found
            // case passes even when lookups are wholly broken -- which they were: rawQuery
            // binds a byte array as its toString(), so matching on the BLOB distribution id
            // found nothing at all. Asserting only the null case hid that completely.
            org.signal.libsignal.protocol.groups.GroupSessionBuilder(senderKeys)
                .create(groupSender, distributionId)
            val storedSenderKeyIsFound = senderKeys.loadSenderKey(groupSender, distributionId) != null

            // The account. The behaviour worth pinning is that allocation advances the
            // counter and writes the keys as one transaction -- the case the research warned
            // about, where a process death between the two hands out an id twice.
            val account = SignalAccountStore(db)
            val beforeLink = !account.credentials().complete
            account.saveCredentials("+15550001234", "aci-uuid", "pni-uuid", 2, "a-password")
            val creds = account.credentials()
            val credentialsRoundTrip = creds.complete && creds.deviceId == 2 && creds.password == "a-password"

            account.saveIdentity(aci, idKeys, 4242)
            val identityRoundTrip = account.identityKeyPair(aci)?.publicKey == idKeys.publicKey
            val registrationIdKept = SignalIdentityKeyStore(db, aci).localRegistrationId == 4242

            var allocated: List<Int> = emptyList()
            account.allocatePreKeyIds(aci, 3) { ids -> allocated = ids }
            var next: List<Int> = emptyList()
            account.allocatePreKeyIds(aci, 3) { ids -> next = ids }
            val idsDoNotRepeat = allocated.intersect(next.toSet()).isEmpty()


            // The facade -- the object the service layer is actually handed. Three things are
            // checked here because all three fail silently in the field.
            val facade = SignalAccountDataStore(
                db, aci, SignalIdentityKeyStore(db, aci), sessions, preKeys, signed, kyber, senderKeys
            )
            val dId = org.whispersystems.signalservice.api.push.DistributionId.from(distributionId)

            // 1. Sender-key sharing round-trips through a BLOB distribution id. A row wrongly
            //    absent re-sends a key needlessly; a row wrongly present means a device that
            //    never receives one and quietly cannot read the group.
            facade.markSenderKeySharedWith(dId, listOf(a1, a2))
            val sharingRoundTrips = facade.getSenderKeySharedWith(dId) == setOf(a1, a2)
            // 2. archiveSession forgets that sharing. This is the cross-store call inside a
            //    libsignal callback that the single reentrant lock exists for: it completing
            //    at all is half the assertion.
            sessions.storeSession(a1, SessionRecord())
            val archiveClearsSharing = try {
                facade.archiveSession(a1)
                facade.getSenderKeySharedWith(dId) == setOf(a2)
            } catch (e: Exception) {
                false
            }
            facade.clearSenderKeySharedWith(listOf(a2))
            val clearedAll = facade.getSenderKeySharedWith(dId).isEmpty()

            // 3. Stale sweeping keeps back the newest keys counting FRESH ones first. Ranking
            //    only the stale keys instead -- the obvious reading -- protects the newest
            //    stale keys forever, so they are never swept and retired keys stay usable.
            //    Five stale, three fresh, keep three: all five stale must go.
            (100..104).forEach { preKeys.storePreKey(it, PreKeyRecord(it, ECKeyPair.generate())) }
            facade.markAllOneTimeEcPreKeysStaleIfNecessary(1_000L)
            (105..107).forEach { preKeys.storePreKey(it, PreKeyRecord(it, ECKeyPair.generate())) }
            facade.deleteAllStaleOneTimeEcPreKeys(2_000L, 3)
            val staleSwept = (100..104).none { preKeys.containsPreKey(it) } &&
                (105..107).all { preKeys.containsPreKey(it) }

            // The pair store. `saveCredentials` above wrote a real ACI and a **bare** PNI,
            // which is the form the provisioning message carries -- so this also pins that a
            // bare PNI resolves, the case a string comparison would silently fail.
            val pair = SignalDataStore(db, account)
            val aciUuid = java.util.UUID.randomUUID()
            val pniUuid = java.util.UUID.randomUUID()
            account.saveCredentials("+15550001234", aciUuid.toString(), pniUuid.toString(), 2, "a-password")
            val aciResolves = pair.get(ServiceId.ACI.from(aciUuid)) === pair.aci()
            val barePniResolves = pair.get(ServiceId.PNI.from(pniUuid)) === pair.pni()
            val strangerRejected = try {
                pair.get(ServiceId.ACI.from(java.util.UUID.randomUUID())); false
            } catch (e: IllegalArgumentException) { true }

            // Safety numbers. The number itself must be stable for a given pair of keys --
            // two people compare them aloud, so a number that varies is worse than none --
            // and accepting a changed key must actually unblock sending, which is the half
            // that did not exist until now: a changed key blocked sends forever with no way
            // back.
            val selfId = org.signal.core.models.ServiceId.ACI.from(java.util.UUID.randomUUID())
            val peerId = org.signal.core.models.ServiceId.ACI.from(java.util.UUID.randomUUID())
            val peerAddr = org.signal.libsignal.protocol.SignalProtocolAddress(peerId.toString(), 1)
            val idStore = SignalIdentityKeyStore(db, aci)
            account.saveIdentity(aci, idKeys, 4242)
            idStore.saveIdentity(peerAddr, IdentityKeyPair.generate().publicKey)
            val sn1 = idStore.identityFor(peerId.toString(), selfId)
            val sn2 = idStore.identityFor(peerId.toString(), selfId)
            val safetyNumberStable = sn1 != null && sn1.safetyNumber == sn2?.safetyNumber
            val safetyNumberShape = sn1?.safetyNumber?.count { it.isDigit() } == 60

            // Now change their key, as a reinstall would.
            idStore.saveIdentity(peerAddr, IdentityKeyPair.generate().publicKey)
            val blockedAfterChange =
                !idStore.isTrustedIdentity(peerAddr, idStore.getIdentity(peerAddr)!!, IdentityKeyStore.Direction.SENDING)
            val accepted = idStore.acceptIdentity(peerId.toString())
            val sendableAfterAccept =
                idStore.isTrustedIdentity(peerAddr, idStore.getIdentity(peerAddr)!!, IdentityKeyStore.Direction.SENDING)
            // Accepting is not verifying.
            val acceptedNotVerified = idStore.identityFor(peerId.toString(), selfId)?.trustLevel ==
                SignalIdentityKeyStore.TRUSTED_UNVERIFIED

            // Group ids. The wire carries a master key; the id is what you get by deriving
            // secret params from it and taking the public group identifier. Base64 of the
            // master key is stable, plausible and wrong -- the bridge files the same group
            // under the derived id, so the two rails would split every group in two.
            val masterKey = ByteArray(32) { it.toByte() }
            val derivedGroupId = ContentNormalizer.groupIdForCheck(masterKey)
            val rawMasterKeyB64 = android.util.Base64.encodeToString(masterKey, android.util.Base64.NO_WRAP)
            val groupIdIsDerived = derivedGroupId.isNotBlank() && derivedGroupId != rawMasterKeyB64
            // A GroupIdentifier is 32 bytes, so its base64 is 44 characters with padding.
            val groupIdLooksRight = derivedGroupId.length == 44

            db.close()
            "${tables.size} tables, seeded=$identities | safety: stable=$safetyNumberStable shape=$safetyNumberShape blocked=$blockedAfterChange accepted=$accepted sendable=$sendableAfterAccept not-verified=$acceptedNotVerified | groups: derived=$groupIdIsDerived shape=$groupIdLooksRight | pair: aci=$aciResolves bare-pni=$barePniResolves stranger-rejected=$strangerRejected | facade: sharing-roundtrip=$sharingRoundTrips " +
                "archive-clears-sharing=$archiveClearsSharing cleared-all=$clearedAll stale-swept=$staleSwept " +
                "| account: empty-before-link=$beforeLink " +
                "credentials=$credentialsRoundTrip identity=$identityRoundTrip regid=$registrationIdKept " +
                "ids-do-not-repeat=$idsDoNotRepeat | senderkeys: unknown-is-null=$unknownSenderKeyIsNull found-after-store=$storedSenderKeyIsFound " +
                "| prekeys: roundtrip=$preKeyRoundTrips " +
                "onetime-consumed=$oneTimeConsumed missing-throws=$missingPreKeyThrows " +
                "signed-keeps-timestamp=$signedKeepsTimestamp kyber-onetime-consumed=$kyberOneTimeGone " +
                "last-resort-survives=$lastResortSurvives | sessions: empty-not-null=$unknownIsEmptyNotNull " +
                "row-without-chain-not-usable=$rowExistsButNotUsable subdevices-exclude-primary=$subDevicesExcludePrimary " +
                "missing-throws=$missingSessionThrows delete-all=$deletedAll | trust: first-sighting=$trustedOnFirstSighting " +
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
