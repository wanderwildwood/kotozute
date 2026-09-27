package com.wanderwildwood.kotozute.signalstore

import okio.ByteString
import org.signal.core.models.ServiceId
import org.signal.storageservice.storage.protos.groups.AccessControl
import org.signal.storageservice.storage.protos.groups.Member
import org.signal.storageservice.storage.protos.groups.local.DecryptedGroupChange
import org.signal.storageservice.storage.protos.groups.local.EnabledState

/**
 * What a change to a group did, one line per thing, for the conversation.
 *
 * Signal Android's `GroupsV2UpdateMessageConverter.translateDecryptedChangeUpdate`, in its
 * order and by its rules -- somebody added by themselves joined by the link; somebody removed
 * by themselves left; an invitation promoted by the invitee was accepted -- trimmed to the
 * changes a person would notice. What is left out (member labels, bans, the phone-number
 * identity swap) reads as "the group was updated", which is upstream's own fallback.
 *
 * Service ids come out as strings, and the words are left to whoever shows them: this has
 * no resources, so it can be tested.
 */
object GroupChangeLines {

    sealed interface Line

    /** [editor] is null wherever upstream's is: the change did not say who made it. */
    data class Renamed(val editor: String?, val title: String) : Line
    data class DescriptionChanged(val editor: String?) : Line
    data class AvatarChanged(val editor: String?) : Line
    /** [seconds] 0 is disappearing messages turned off. */
    data class Timer(val editor: String?, val seconds: Int) : Line
    data class Added(val editor: String?, val member: String) : Line
    data class JoinedByLink(val member: String) : Line
    data class Joined(val member: String) : Line
    data class AcceptedInvite(val member: String) : Line
    data class Invited(val editor: String?, val member: String?, val count: Int) : Line
    data class Removed(val editor: String?, val member: String) : Line
    data class Left(val member: String) : Line
    data class Admin(val editor: String?, val member: String, val granted: Boolean) : Line
    data class AdminsOnly(val editor: String?, val on: Boolean) : Line
    data class WhoCanEdit(val editor: String?, val adminsOnly: Boolean) : Line
    data class WhoCanAdd(val editor: String?, val adminsOnly: Boolean) : Line
    data class Link(val editor: String?, val on: Boolean, val approval: Boolean) : Line
    data class AskedToJoin(val member: String) : Line
    data class Approved(val editor: String?, val member: String) : Line
    data class Ended(val editor: String?) : Line
    object Updated : Line

    fun describe(change: DecryptedGroupChange, self: String): List<Line> {
        val editor = idOf(change.editorServiceIdBytes)
        val out = mutableListOf<Line>()

        change.newMembers.forEach { m ->
            val member = idOf(m.aciBytes) ?: return@forEach
            out += if (editor != null && member == editor) JoinedByLink(member) else Added(editor, member)
        }
        change.modifyMemberRoles.forEach { r ->
            val member = idOf(r.aciBytes) ?: return@forEach
            out += Admin(editor, member, r.role == Member.Role.ADMINISTRATOR)
        }
        // Invitations: an invitation of us is named; others are counted, as upstream counts
        // them, since an invitee not yet in the group may have no name here.
        val invited = change.newPendingMembers.mapNotNull { idOf(it.serviceIdBytes) }
        if (self in invited) out += Invited(editor, self, 1)
        val others = invited.count { it != self }
        if (others > 0) out += Invited(editor, null, others)
        change.promotePendingMembers.forEach { m ->
            val member = idOf(m.aciBytes) ?: return@forEach
            out += when {
                editor == null -> Joined(member)
                editor == member -> AcceptedInvite(member)
                else -> Added(editor, member)
            }
        }
        change.newTitle?.let { out += Renamed(editor, it.value_) }
        change.newDescription?.let { out += DescriptionChanged(editor) }
        change.newAvatar?.let { out += AvatarChanged(editor) }
        change.newTimer?.let { out += Timer(editor, it.duration) }
        accessOf(change.newAttributeAccess)?.let { out += WhoCanEdit(editor, it) }
        accessOf(change.newMemberAccess)?.let { out += WhoCanAdd(editor, it) }
        when (change.newInviteLinkAccess) {
            AccessControl.AccessRequired.ANY -> out += Link(editor, on = true, approval = false)
            AccessControl.AccessRequired.ADMINISTRATOR -> out += Link(editor, on = true, approval = true)
            AccessControl.AccessRequired.UNSATISFIABLE -> out += Link(editor, on = false, approval = false)
            else -> Unit
        }
        change.newRequestingMembers.forEach { m -> idOf(m.aciBytes)?.let { out += AskedToJoin(it) } }
        change.promoteRequestingMembers.forEach { m -> idOf(m.aciBytes)?.let { out += Approved(editor, it) } }
        when (change.newIsAnnouncementGroup) {
            EnabledState.ENABLED -> out += AdminsOnly(editor, true)
            EnabledState.DISABLED -> out += AdminsOnly(editor, false)
            else -> Unit
        }
        change.deleteMembers.forEach { bytes ->
            val member = idOf(bytes) ?: return@forEach
            out += if (member == editor) Left(member) else Removed(editor, member)
        }
        if (change.terminateGroup) out += Ended(editor)
        return out.ifEmpty { listOf(Updated) }
    }

    /** Upstream's `translateGv2AccessLevel`, as "admins only": null where nothing changed. */
    private fun accessOf(access: AccessControl.AccessRequired): Boolean? = when (access) {
        AccessControl.AccessRequired.ADMINISTRATOR -> true
        AccessControl.AccessRequired.MEMBER, AccessControl.AccessRequired.ANY -> false
        else -> null
    }

    private fun idOf(bytes: ByteString): String? =
        if (bytes.size == 0) null
        else ServiceId.parseOrNull(bytes.toByteArray())?.takeUnless { it.isUnknown }?.toString()
}
