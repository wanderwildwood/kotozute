package com.wanderwildwood.kotozute.signalstore

/**
 * One change to a group, as upstream's `GroupManagerV2` makes them. Each becomes one signed
 * change on the server (see [SignalGroups.edit]), told to the members with its signature.
 */
internal sealed interface GroupEdit {
    data class Rename(val title: String) : GroupEdit
    data class Describe(val text: String) : GroupEdit
    /** Members to add; anybody whose profile key this phone lacks is invited instead. */
    data class Add(val acis: List<String>) : GroupEdit
    data class Remove(val aci: String) : GroupEdit
    data class Admin(val aci: String, val admin: Boolean) : GroupEdit
    data class EditInfoAdminsOnly(val on: Boolean) : GroupEdit
    data class AddMembersAdminsOnly(val on: Boolean) : GroupEdit
    data class SendAdminsOnly(val on: Boolean) : GroupEdit
    data class SetLink(val state: SignalGroups.Group.Link) : GroupEdit
    data object ResetLink : GroupEdit
    data class Approve(val aci: String) : GroupEdit
    data class Deny(val aci: String) : GroupEdit
    data object AcceptInvite : GroupEdit
    data object DeclineInvite : GroupEdit
    /** Leaving, handing administration to [newAdmin] when we are the last administrator. */
    data class Leave(val newAdmin: String?) : GroupEdit
}
