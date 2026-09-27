package com.wanderwildwood.kotozute.signalstore

import com.wanderwildwood.kotozute.repository.GroupNotMade
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
        val expiresInSeconds: Long = 0,
        /**
         * Which version of the group's state this is.
         *
         * Sent with every group message so a recipient whose own copy is older knows to go and
         * catch up. Stamped 0 -- which was the case here -- it is never newer than anybody's,
         * so nobody ever refreshes, and a recipient who has not yet seen us added to the group
         * discards our messages as coming from a non-member.
         */
        val revision: Int = 0,
        /** Only administrators may post. */
        val announcementOnly: Boolean = false,
        /** Service ids of the members who are administrators. */
        val admins: Set<String> = emptySet(),
        val description: String = "",
        /** Invited and not yet joined, by service id -- an account id or a phone-number identity. */
        val pending: List<String> = emptyList(),
        /** Asked to join by the group's link, waiting for an administrator. */
        val requesting: List<String> = emptyList(),
        /** Only administrators may change the name, description, picture and timer. */
        val editInfoAdminsOnly: Boolean = false,
        /** Only administrators may add members. */
        val addMembersAdminsOnly: Boolean = false,
        /** The group link: off, on, or on with an administrator approving each request. */
        val link: Link = Link.OFF,
        val linkPassword: ByteArray = ByteArray(0),
        /** How the server names each pending invitation: what declining one has to hand back. */
        val pendingCiphertexts: Map<String, ByteArray> = emptyMap()
    ) {
        enum class Link { OFF, ON, APPROVAL }
    }

    /**
     * Credentials are issued per day and returned a week at a time, so they are fetched once
     * and kept. Asking per group message would be a round trip for something that does not
     * change until midnight.
     *
     * ⚠ **On the companion, not the instance, and that is the whole point of it.** This class
     * is built fresh at every one of its ten call sites -- `SignalGroups(connection, account,
     * contacts).fetch(...)` -- so an instance-level map was never read twice, and every group
     * message, reaction, delete and name lookup paid for its own credential fetch. The comment
     * above described what the field was for and not what it did.
     *
     * The identical fault, with the identical fix, is written up in [SealedSender] for the
     * sender certificate. Finding it twice in one codebase is the argument for looking at every
     * cache on a class that is constructed per operation.
     */
    private val credentialsByDay get() = sharedCredentialsByDay

    /**
     * What happened when this device asked the server about a group.
     *
     * ⚠ **"Not in it any more" and "could not ask" are different facts**, and collapsing them
     * into a null told a reader to try again for ever about something that is permanent. See
     * [fetchOutcome].
     */
    sealed interface Outcome {
        data class Got(val group: Group) : Outcome

        /** The server says this account is not a member. A fact, not an error. */
        object NotAMember : Outcome

        /** The group itself is gone. */
        object Gone : Outcome

        /** Anything else -- no network, a server having a moment. Worth trying again. */
        data class Unknown(val why: String) : Outcome
    }

    /**
     * Asks the server about a group and says which kind of answer came back.
     *
     * The server answers 403 when this account is not in the group, which the library raises as
     * `NotInGroupException` (and `GroupTerminatedException` when the group itself has ended).
     * Upstream treats both as facts to act on rather than failures to retry -- `GroupManagerV2`
     * and `GroupJoinRepository` both catch them by name.
     */
    fun fetchOutcome(masterKeyBytes: ByteArray): Outcome = try {
        fetchOrThrow(masterKeyBytes)?.let { Outcome.Got(it) }
            ?: Outcome.Unknown("no group credentials for today")
    } catch (t: org.whispersystems.signalservice.internal.push.exceptions.NotInGroupException) {
        Timber.i("signal groups: the server says this account is not in that group")
        Outcome.NotAMember
    } catch (t: org.whispersystems.signalservice.internal.push.exceptions.GroupTerminatedException) {
        Timber.i("signal groups: that group has ended")
        Outcome.Gone
    } catch (t: Throwable) {
        Timber.w(t, "signal groups: could not fetch group details")
        Outcome.Unknown(t.message ?: t::class.java.simpleName)
    }

    /**
     * The group, or null for any reason at all.
     *
     * Kept because the receive path must **fail open** -- see [senderIsInGroup] in the
     * receiver: a group whose state cannot be read still delivers its messages, and turning
     * "could not ask" into "not a member" there would drop real messages from real people.
     * Only the send path needs to tell the two apart, and it uses [fetchOutcome].
     */
    fun fetch(masterKeyBytes: ByteArray): Group? =
        (fetchOutcome(masterKeyBytes) as? Outcome.Got)?.group

    private fun fetchOrThrow(masterKeyBytes: ByteArray): Group? = run {
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
            revision = group.revision,
            // Both come from the group's own state. Ignored, a non-admin's message to an
            // announcement group is written down and shown as sent while every recipient
            // silently discards it -- a message lost behind a tick, which is the worst way
            // for one to be lost.
            announcementOnly = group.isAnnouncementGroup ==
                org.signal.storageservice.storage.protos.groups.local.EnabledState.ENABLED,
            admins = group.members
                .filter { it.role == org.signal.storageservice.storage.protos.groups.Member.Role.ADMINISTRATOR }
                .mapNotNull { ServiceId.parseOrNull(it.aciBytes?.toByteArray())?.toString() }
                .toSet(),
            // ACIs only. A member known to the server by PNI has not yet been resolved to an
            // account we can open a session with, and including them would produce a send
            // that fails partway with no way to say who it failed for.
            members = group.members.mapNotNull { member ->
                ServiceId.parseOrNull(member.aciBytes?.toByteArray())?.toString()
            },
            description = group.description,
            pending = group.pendingMembers.mapNotNull { ServiceId.parseOrNull(it.serviceIdBytes.toByteArray())?.toString() },
            requesting = group.requestingMembers.mapNotNull { ServiceId.parseOrNull(it.aciBytes.toByteArray())?.toString() },
            editInfoAdminsOnly = group.accessControl?.attributes ==
                org.signal.storageservice.storage.protos.groups.AccessControl.AccessRequired.ADMINISTRATOR,
            addMembersAdminsOnly = group.accessControl?.members ==
                org.signal.storageservice.storage.protos.groups.AccessControl.AccessRequired.ADMINISTRATOR,
            link = when (group.accessControl?.addFromInviteLink) {
                org.signal.storageservice.storage.protos.groups.AccessControl.AccessRequired.ANY -> Group.Link.ON
                org.signal.storageservice.storage.protos.groups.AccessControl.AccessRequired.ADMINISTRATOR -> Group.Link.APPROVAL
                else -> Group.Link.OFF
            },
            linkPassword = group.inviteLinkPassword.toByteArray(),
            pendingCiphertexts = group.pendingMembers.mapNotNull { p ->
                ServiceId.parseOrNull(p.serviceIdBytes.toByteArray())?.toString()?.let { it to p.serviceIdCipherText.toByteArray() }
            }.toMap()
        ).also {
            harvest(group.members)
            Timber.i("signal groups: fetched a group with %d members", it.members.size)
        }
    }

    /** How a change to a group went. See [change]. */
    sealed interface Changed {
        /** Made. [revision] is the group's new one; [signedChange] is the server's copy. */
        class Done(val revision: Int, val members: List<String>, val signedChange: ByteArray) : Changed

        /** The group already says so; there was nothing to change. */
        object Unneeded : Changed

        /** The group lets only its administrators do this, and this account is not one. */
        object NotAllowed : Changed

        /** This account is no longer in the group. */
        object NotAMember : Changed

        /** Anything else. Worth trying again. */
        data class Failed(val why: String) : Changed
    }

    /**
     * Makes one change to a group on the server: upstream's `GroupManagerV2.commitChange`.
     *
     * [build] is handed the group as it stands and returns the change, or null when there is
     * nothing to do. The change is stamped with the next revision, and if somebody else's
     * change got there first the server refuses it as a conflict -- then the group is read
     * again and the change rebuilt against it, as upstream's conflict resolution does.
     */
    fun change(
        masterKeyBytes: ByteArray,
        build: (Group, org.whispersystems.signalservice.api.groupsv2.GroupsV2Operations.GroupOperations) ->
            org.signal.storageservice.storage.protos.groups.GroupChange.Actions.Builder?
    ): Changed {
        val secretParams = GroupSecretParams.deriveFromMasterKey(GroupMasterKey(masterKeyBytes))
        repeat(CHANGE_ATTEMPTS) {
            val group = when (val got = fetchOutcome(masterKeyBytes)) {
                is Outcome.Got -> got.group
                Outcome.NotAMember, Outcome.Gone -> return Changed.NotAMember
                is Outcome.Unknown -> return Changed.Failed(got.why)
            }
            val actions = build(group, connection.groupOperations.forGroup(secretParams))
                ?: return Changed.Unneeded
            val next = group.revision + 1
            val auth = authorizationFor(secretParams, todaySeconds())
                ?: return Changed.Failed("no group authorization")
            try {
                val response = connection.groups.patchGroup(
                    actions.version(next).build(), auth, java.util.Optional.empty()
                )
                val signed = response.group_change ?: return Changed.Failed("the server sent no change back")
                Timber.i("signal groups: changed a group, now at revision %d", next)
                return Changed.Done(next, group.members, signed.encode())
            } catch (e: org.whispersystems.signalservice.api.push.exceptions.ConflictException) {
                Timber.i("signal groups: somebody else changed the group first; trying again")
            } catch (e: org.whispersystems.signalservice.internal.push.exceptions.NotInGroupException) {
                return Changed.NotAMember
            } catch (e: org.whispersystems.signalservice.api.push.exceptions.AuthorizationFailedException) {
                Timber.i("signal groups: not allowed to make that change")
                return Changed.NotAllowed
            } catch (t: Throwable) {
                Timber.w(t, "signal groups: could not change the group")
                return Changed.Failed(t.message ?: t::class.java.simpleName)
            }
        }
        return Changed.Failed("kept conflicting with other changes")
    }

    /**
     * Makes [edit] to the group: upstream's `GroupManagerV2` for each kind. See [GroupEdit].
     * A change the group already reflects comes back [Changed.Unneeded].
     */
    internal fun edit(masterKeyBytes: ByteArray, edit: GroupEdit): Changed {
        val credentials = accounts.credentials()
        val self = ServiceId.ACI.parseOrNull(credentials.aci) ?: return Changed.Failed("no account id")
        fun aci(s: String) = ServiceId.ACI.parseOrNull(s)
        fun newPassword() = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        return change(masterKeyBytes) { group, ops ->
            when (edit) {
                is GroupEdit.Rename -> edit.title.trim().takeIf { it.isNotEmpty() && it != group.title }
                    ?.let { ops.createModifyGroupTitle(it) }
                is GroupEdit.Describe -> edit.text.trim().takeIf { it != group.description }
                    ?.let { ops.createModifyGroupDescription(it) }
                is GroupEdit.Add -> edit.acis.mapNotNull(::aci).filter { it.toString() !in group.members }
                    .takeIf { it.isNotEmpty() }
                    ?.map { candidateFor(it) ?: org.whispersystems.signalservice.api.groupsv2.GroupCandidate(it, java.util.Optional.empty()) }
                    ?.let { ops.createModifyGroupMembershipChange(it.toSet(), emptySet(), self) }
                is GroupEdit.Remove -> aci(edit.aci)?.takeIf { edit.aci in group.members }
                    ?.let { ops.createRemoveMembersChange(setOf(it), false, emptyList()) }
                is GroupEdit.Admin -> aci(edit.aci)?.takeIf { (edit.aci in group.admins) != edit.admin }
                    ?.let {
                        ops.createChangeMemberRole(
                            it,
                            if (edit.admin) org.signal.storageservice.storage.protos.groups.Member.Role.ADMINISTRATOR
                            else org.signal.storageservice.storage.protos.groups.Member.Role.DEFAULT
                        )
                    }
                is GroupEdit.EditInfoAdminsOnly -> if (group.editInfoAdminsOnly == edit.on) null
                    else ops.createChangeAttributesRights(if (edit.on) org.signal.storageservice.storage.protos.groups.AccessControl.AccessRequired.ADMINISTRATOR else org.signal.storageservice.storage.protos.groups.AccessControl.AccessRequired.MEMBER)
                is GroupEdit.AddMembersAdminsOnly -> if (group.addMembersAdminsOnly == edit.on) null
                    else ops.createChangeMembershipRights(if (edit.on) org.signal.storageservice.storage.protos.groups.AccessControl.AccessRequired.ADMINISTRATOR else org.signal.storageservice.storage.protos.groups.AccessControl.AccessRequired.MEMBER)
                is GroupEdit.SendAdminsOnly -> if (group.announcementOnly == edit.on) null
                    else ops.createAnnouncementGroupChange(edit.on)
                is GroupEdit.SetLink -> when {
                    edit.state == group.link -> null
                    edit.state == Group.Link.OFF -> ops.createChangeJoinByLinkRights(org.signal.storageservice.storage.protos.groups.AccessControl.AccessRequired.UNSATISFIABLE)
                    else -> {
                        val rights = if (edit.state == Group.Link.APPROVAL) org.signal.storageservice.storage.protos.groups.AccessControl.AccessRequired.ADMINISTRATOR else org.signal.storageservice.storage.protos.groups.AccessControl.AccessRequired.ANY
                        // A link never made has no password yet; upstream makes one as it turns on.
                        if (group.linkPassword.isEmpty()) ops.createModifyGroupLinkPasswordAndRightsChange(newPassword(), rights)
                        else ops.createChangeJoinByLinkRights(rights)
                    }
                }
                GroupEdit.ResetLink -> ops.createModifyGroupLinkPasswordChange(newPassword())
                is GroupEdit.Approve -> aci(edit.aci)?.takeIf { edit.aci in group.requesting }
                    ?.let { ops.createApproveGroupJoinRequest(setOf(it.rawUuid)) }
                is GroupEdit.Deny -> aci(edit.aci)?.takeIf { edit.aci in group.requesting }
                    ?.let { ops.createRefuseGroupJoinRequest(setOf(it), false, emptyList()) }
                GroupEdit.AcceptInvite -> selfCredential(self)
                    ?.takeIf { self.toString() in group.pending }
                    ?.let { ops.createAcceptInviteChange(it) }
                GroupEdit.DeclineInvite -> group.pendingCiphertexts[self.toString()]?.takeIf { self.toString() in group.pending }
                    ?.let { ops.createRemoveInvitationChange(setOf(org.signal.libsignal.zkgroup.groups.UuidCiphertext(it))) }
                is GroupEdit.Leave -> if (self.toString() !in group.members) null
                    else ops.createLeaveAndPromoteMembersToAdmin(self, listOfNotNull(edit.newAdmin?.let(::aci)?.rawUuid))
            }
        }
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

    /**
     * Makes a new group and puts it on the server.
     *
     * A port of `GroupManagerV2.createGroupOnServer`, step for step, minus the parts this app
     * has no equivalent of (avatars, the group record's own database row).
     *
     * ⚠ **A group is built from credentials, not from names.** Every member is committed as an
     * `ExpiringProfileKeyCredential` -- a zero-knowledge proof that this account holds their
     * profile key -- so a member whose credential cannot be fetched cannot be *added*, only
     * **invited**, and the server decides which by what is in the request. That is why this
     * fetches a credential per member rather than trusting the contact row.
     *
     * The one that must succeed is **self**: upstream refuses outright without it
     * ("Cannot create a V2 group as self does not have a versioned profile"), because a group
     * whose creator cannot prove their own profile key is not a group anybody can verify.
     *
     * @param title what to call it. The server never sees it in the clear; it is encrypted
     *   under the group's own parameters, like everything else here.
     * @param memberAcis everybody else. The creator is added automatically and must not be in
     *   this list.
     * @return the new group's master key, which is the only handle anything else needs -- a
     *   group message carries it and nothing more.
     * @throws GroupNotMade saying which way it failed, for every way this can. They mean
     *   different things; a null return said the same nothing for all of them, and the reader
     *   was left at a dead end. The screen words the kind, in the reader's language.
     */
    fun create(title: String, memberAcis: List<String>): GroupMasterKey {
        val credentials = accounts.credentials()
        val selfAci = ServiceId.ACI.parseOrNull(credentials.aci)
            ?: throw GroupNotMade(GroupNotMade.Why.NOT_LINKED)

        val self = candidateFor(selfAci)
        if (self == null || !self.hasValidProfileKeyCredential()) {
            // Upstream repairs this by uploading its own profile and trying again. This app
            // does not write its own profile, so the honest answer is to say what is missing
            // rather than to send a request the server will refuse.
            Timber.w("signal groups: this account has no profile credential, so it cannot make a group")
            throw GroupNotMade(GroupNotMade.Why.NO_PROFILE)
        }

        val members = memberAcis
            .mapNotNull { ServiceId.ACI.parseOrNull(it) }
            .filter { it != selfAci }
            .distinct()
        if (members.isEmpty()) {
            throw GroupNotMade(GroupNotMade.Why.NOBODY_ELSE)
        }

        // A member without a credential is still a candidate -- the server turns them into an
        // invitation rather than a member, which is Signal's own behaviour and is why the
        // credential is Optional on the type.
        val candidates = members.map { aci ->
            candidateFor(aci) ?: org.whispersystems.signalservice.api.groupsv2.GroupCandidate(
                aci, java.util.Optional.empty()
            )
        }.toSet()

        val secretParams = GroupSecretParams.generate()
        val newGroup = runCatching {
            connection.groupOperations.createNewGroup(
                secretParams,
                title,
                java.util.Optional.empty(),
                self,
                candidates,
                org.signal.storageservice.storage.protos.groups.Member.Role.DEFAULT,
                0
            )
        }.onFailure { Timber.w(it, "signal groups: could not build the new group") }.getOrNull()
            ?: throw GroupNotMade(GroupNotMade.Why.NOT_PUT_TOGETHER)

        val auth = authorizationFor(secretParams, todaySeconds())
            ?: throw GroupNotMade(GroupNotMade.Why.NOT_AUTHORIZED)

        return runCatching {
            connection.groups.putNewGroup(newGroup, auth)
            val masterKey = secretParams.masterKey
            Timber.i(
                "signal groups: made a group of %d with %d invited",
                candidates.count { it.hasValidProfileKeyCredential() } + 1,
                candidates.count { !it.hasValidProfileKeyCredential() }
            )
            masterKey
        }.onFailure { Timber.w(it, "signal groups: the server would not take the new group") }
            .getOrElse { throw GroupNotMade(GroupNotMade.Why.SERVER_REFUSED) }
    }

    /**
     * A member, with the proof that this account holds their profile key.
     *
     * Null when there is no profile key on file or the server will not issue a credential for
     * it; the caller decides whether that means "invite them" or "give up", which is the
     * distinction upstream draws too.
     */
    /** This account's own credential, from its own profile key: what accepting an invitation proves. */
    private fun selfCredential(self: ServiceId.ACI): org.signal.libsignal.zkgroup.profiles.ExpiringProfileKeyCredential? {
        val key = accounts.profileKey()?.let { runCatching { org.signal.libsignal.zkgroup.profiles.ProfileKey(it) }.getOrNull() }
            ?: return null
        return runCatching {
            kotlinx.coroutines.runBlocking { connection.profiles.getVersionedProfileAndCredential(self, key, null) }
        }.getOrNull()
            ?.let { it as? org.signal.network.NetworkResult.Success }
            ?.result?.second
    }

    private fun candidateFor(
        aci: ServiceId.ACI
    ): org.whispersystems.signalservice.api.groupsv2.GroupCandidate? {
        val keyBytes = contacts?.profileKeyFor(aci.toString()) ?: return null
        val profileKey = runCatching {
            org.signal.libsignal.zkgroup.profiles.ProfileKey(keyBytes)
        }.getOrNull() ?: return null

        val credential = runCatching {
            kotlinx.coroutines.runBlocking {
                connection.profiles.getVersionedProfileAndCredential(aci, profileKey, null)
            }
        }.getOrNull()
            ?.let { it as? org.signal.network.NetworkResult.Success }
            ?.result
            ?.second
            ?: return null

        return org.whispersystems.signalservice.api.groupsv2.GroupCandidate(
            aci, java.util.Optional.of(credential)
        )
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

                // The third argument is new: `authCredentialSalt`, from
                // `GroupsV2Api.getGroupsV2AuthorizationString` (six parameters now, five
                // before). It is read only when `pni` is null, and `pni` cannot be null here
                // -- the parse above returns rather than reaching this. It is passed anyway
                // rather than hard-coded null, so that the day this app supports an account
                // without a phone number, the value is already the right one.
                return connection.groups.getGroupsV2AuthorizationString(
                    aci, pni, accounts.authCredentialSalt(), today, secretParams,
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

    companion object {
        /** How many times a change is rebuilt after losing a race: upstream's five. */
        private const val CHANGE_ATTEMPTS = 5

        /**
         * The group credentials this process holds, by UTC day.
         *
         * Process-wide because the class that reads it is not: see the note on
         * [credentialsByDay]. A `ConcurrentHashMap` rather than a plain one because the group
         * paths run on the receive thread, the send thread and the periodic round.
         */
        private val sharedCredentialsByDay = java.util.concurrent.ConcurrentHashMap<Long, Any>()

        /**
         * Throws away every held credential.
         *
         * ⚠ **Called when this account's phone-number identity changes.** A group credential is
         * issued against the ACI *and* the PNI, so a number change invalidates every one of
         * them. Upstream clears them at exactly that point --
         * `ChangeNumberRepository.applyLocalNumberChange` calls
         * `AppDependencies.groupsV2Authorization.clear()` in the same breath as storing the new
         * identity.
         *
         * It mattered less while the cache was per-instance, because nothing survived a single
         * call to be stale. Now that it works, this is what keeps it honest.
         */
        fun forgetCredentials() {
            sharedCredentialsByDay.clear()
        }
    }
}
