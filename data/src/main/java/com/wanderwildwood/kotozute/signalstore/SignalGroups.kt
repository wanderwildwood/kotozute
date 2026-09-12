package com.wanderwildwood.kotozute.signalstore

import org.signal.core.models.ServiceId
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * What a group is called, and who is in it.
 *
 * A group message carries only the master key. Everything human about the group -- its name,
 * its members -- lives on the server, encrypted under parameters derived from that key, and
 * has to be fetched. Until it is, a group can be received from and displayed only as its own
 * identifier, which is what the inbox was showing.
 *
 * The members matter as much as the name: sending to a group means encrypting to each member
 * in turn, so there is no group send without this.
 */
internal class SignalGroups(
    private val connection: SignalConnection,
    private val accounts: SignalAccountStore,
    /**
     * Where what the group says about its members is kept.
     *
     * Group state is a source of two things this app otherwise struggles for: a member's
     * **profile key**, which is the only thing that decrypts their name, and the pairing of
     * their account id with their **phone-number identity**. Both come from the server's own
     * copy of the group, decrypted with the group key -- not from anybody's assertion.
     */
    private val contacts: SignalContactStore? = null
) {

    data class Group(
        val title: String,
        val members: List<String>,
        /** The group's disappearing-messages timer, in seconds. 0 when messages stay. */
        val expiresInSeconds: Long = 0
    )

    /**
     * Credentials are issued per day and returned a week at a time, so they are fetched once
     * and kept. Asking per group message would be a round trip for something that does not
     * change until midnight.
     */
    private val credentialsByDay = mutableMapOf<Long, Any?>()

    fun fetch(masterKeyBytes: ByteArray): Group? = try {
        val masterKey = GroupMasterKey(masterKeyBytes)
        val secretParams = GroupSecretParams.deriveFromMasterKey(masterKey)
        val today = todaySeconds()
        val auth = authorizationFor(secretParams, today)
            ?: return null

        val response = connection.groups.getGroup(secretParams, auth)
        val group = response.group

        Group(
            title = group.title.orEmpty(),
            // A property of the group, agreed by its members and held in its state -- not
            // something a message has to carry. Read here so a group thread knows its timer
            // without waiting for somebody to change it.
            expiresInSeconds = (group.disappearingMessagesTimer?.duration ?: 0).toLong(),
            // ACIs only. A member known to the server by PNI has not yet been resolved to an
            // account we can open a session with, and including them would produce a send
            // that fails partway with no way to say who it failed for.
            members = group.members.mapNotNull { member ->
                ServiceId.parseOrNull(member.aciBytes?.toByteArray())?.toString()
            }
        ).also {
            harvest(group.members)
            Timber.i("signal groups: fetched a group with %d members", it.members.size)
        }
    } catch (t: Throwable) {
        // A 403 means we are not in the group any more, which is a fact rather than an error;
        // everything else is logged and treated the same way, because a group whose details
        // cannot be fetched should still receive messages.
        Timber.w(t, "signal groups: could not fetch group details")
        null
    }

    /**
     * Keeps what the group knows about the people in it.
     *
     * A profile key is the only thing that turns a service id into a name, and for somebody
     * this account has never exchanged a message with, a shared group is the one place it
     * turns up -- the contacts sync does not carry it and discovery does not return it. The
     * account-to-phone-number pairing here is worth the same: it comes from the server's
     * group state rather than from a claim on the wire, so it can be trusted the same way a
     * storage record can.
     *
     * Best effort throughout. Nothing here should be able to fail a send to the group, which
     * is what this fetch is actually for.
     */
    private fun harvest(members: List<org.signal.storageservice.storage.protos.groups.local.DecryptedMember>) {
        val store = contacts ?: return
        members.forEach { member ->
            val aci = ServiceId.parseOrNull(member.aciBytes?.toByteArray())?.toString()
                ?.takeIf { it.isNotBlank() } ?: return@forEach

            member.profileKey?.takeIf { it.size > 0 }?.let { key ->
                // Name deliberately null: this says how to read their name, not what it is.
                // The contact store keeps whatever name it already had rather than letting a
                // blank overwrite it, and SignalProfiles picks the key up on its next pass.
                runCatching {
                    store.store(
                        listOf(
                            SignalContactStore.Contact(
                                serviceId = aci, e164 = null, name = null,
                                profileKey = key.toByteArray()
                            )
                        )
                    )
                }.onFailure { Timber.w(it, "signal groups: a member's profile key would not keep") }
            }

            ServiceId.parseOrNull(member.pniBytes?.toByteArray())?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.let { pni ->
                    runCatching { store.pair(pni, aci) }
                        .onFailure { Timber.w(it, "signal groups: a member's pairing would not keep") }
                }
        }
    }

    private fun authorizationFor(
        secretParams: GroupSecretParams,
        today: Long
    ): org.whispersystems.signalservice.api.groupsv2.GroupsV2AuthorizationString? {
        val credentials = accounts.credentials()
        val aci = ServiceId.ACI.parseOrNull(credentials.aci) ?: return null
        val pni = ServiceId.PNI.parseOrNull(credentials.pni) ?: return null

        repeat(2) { attempt ->
            try {
                @Suppress("UNCHECKED_CAST")
                val forToday = (credentialsByDay[today]
                    ?: connection.groups.getCredentials(today).authCredentialWithPniResponseHashMap[today]
                        ?.also { credentialsByDay[today] = it })
                    ?: return null

                return connection.groups.getGroupsV2AuthorizationString(
                    aci, pni, today, secretParams,
                    forToday as org.signal.libsignal.zkgroup.auth.AuthCredentialWithPniResponse
                )
            } catch (t: Throwable) {
                // Credentials expire, and the failure looks like a verification error rather
                // than an expiry. One retry with fresh ones separates the two; a second
                // failure is a real one.
                if (attempt == 0) {
                    Timber.d("signal groups: group credentials rejected, refreshing")
                    credentialsByDay.clear()
                } else {
                    Timber.w(t, "signal groups: could not build group authorization")
                }
            }
        }
        return null
    }

    /**
     * Credentials are scoped to a UTC day, and the server checks the boundary. Computing this
     * in local time would produce a credential the server rejects for part of every day,
     * varying by timezone -- which reads as an intermittent network fault.
     */
    private fun todaySeconds(): Long =
        TimeUnit.DAYS.toSeconds(TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis()))
}
