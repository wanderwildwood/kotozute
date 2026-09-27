package com.wanderwildwood.kotozute.signalstore

import com.wanderwildwood.kotozute.signalstore.GroupChangeLines.Added
import com.wanderwildwood.kotozute.signalstore.GroupChangeLines.Admin
import com.wanderwildwood.kotozute.signalstore.GroupChangeLines.Invited
import com.wanderwildwood.kotozute.signalstore.GroupChangeLines.JoinedByLink
import com.wanderwildwood.kotozute.signalstore.GroupChangeLines.Left
import com.wanderwildwood.kotozute.signalstore.GroupChangeLines.Removed
import com.wanderwildwood.kotozute.signalstore.GroupChangeLines.Renamed
import com.wanderwildwood.kotozute.signalstore.GroupChangeLines.Timer
import com.wanderwildwood.kotozute.signalstore.GroupChangeLines.Updated
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Test
import org.signal.core.models.ServiceId
import org.signal.storageservice.storage.protos.groups.Member
import org.signal.storageservice.storage.protos.groups.local.DecryptedGroupChange
import org.signal.storageservice.storage.protos.groups.local.DecryptedMember
import org.signal.storageservice.storage.protos.groups.local.DecryptedModifyMemberRole
import org.signal.storageservice.storage.protos.groups.local.DecryptedPendingMember
import org.signal.storageservice.storage.protos.groups.local.DecryptedString
import org.signal.storageservice.storage.protos.groups.local.DecryptedTimer
import java.util.UUID

class GroupChangeLinesTest {

    private val alice = aci("11111111-1111-1111-1111-111111111111")
    private val bob = aci("22222222-2222-2222-2222-222222222222")
    private val me = aci("33333333-3333-3333-3333-333333333333")

    private fun aci(uuid: String) = ServiceId.ACI.from(UUID.fromString(uuid))
    private fun ServiceId.bytes(): ByteString = toByteArray().toByteString()
    private fun id(s: ServiceId) = s.toString()

    @Test
    fun `a rename and a timer, in upstream's order`() {
        val change = DecryptedGroupChange(
            editorServiceIdBytes = alice.bytes(),
            newTimer = DecryptedTimer(duration = 604800),
            newTitle = DecryptedString(value_ = "Picnic")
        )
        assertEquals(
            listOf(Renamed(id(alice), "Picnic"), Timer(id(alice), 604800)),
            GroupChangeLines.describe(change, id(me))
        )
    }

    @Test
    fun `adding yourself is joining by the link, adding someone else is adding`() {
        val change = DecryptedGroupChange(
            editorServiceIdBytes = alice.bytes(),
            newMembers = listOf(DecryptedMember(aciBytes = alice.bytes()), DecryptedMember(aciBytes = bob.bytes()))
        )
        assertEquals(
            listOf(JoinedByLink(id(alice)), Added(id(alice), id(bob))),
            GroupChangeLines.describe(change, id(me))
        )
    }

    @Test
    fun `removing yourself is leaving`() {
        val change = DecryptedGroupChange(
            editorServiceIdBytes = alice.bytes(),
            deleteMembers = listOf(alice.bytes(), bob.bytes())
        )
        assertEquals(
            listOf(Left(id(alice)), Removed(id(alice), id(bob))),
            GroupChangeLines.describe(change, id(me))
        )
    }

    @Test
    fun `admin granted and revoked`() {
        val change = DecryptedGroupChange(
            editorServiceIdBytes = alice.bytes(),
            modifyMemberRoles = listOf(
                DecryptedModifyMemberRole(aciBytes = bob.bytes(), role = Member.Role.ADMINISTRATOR),
                DecryptedModifyMemberRole(aciBytes = me.bytes(), role = Member.Role.DEFAULT)
            )
        )
        assertEquals(
            listOf(Admin(id(alice), id(bob), true), Admin(id(alice), id(me), false)),
            GroupChangeLines.describe(change, id(me))
        )
    }

    @Test
    fun `invitations name us and count the rest`() {
        val change = DecryptedGroupChange(
            editorServiceIdBytes = alice.bytes(),
            newPendingMembers = listOf(
                DecryptedPendingMember(serviceIdBytes = me.bytes()),
                DecryptedPendingMember(serviceIdBytes = bob.bytes())
            )
        )
        assertEquals(
            listOf(Invited(id(alice), id(me), 1), Invited(id(alice), null, 1)),
            GroupChangeLines.describe(change, id(me))
        )
    }

    @Test
    fun `a change with no editor still describes, without a name`() {
        val change = DecryptedGroupChange(newTitle = DecryptedString(value_ = "X"))
        assertEquals(listOf(Renamed(null, "X")), GroupChangeLines.describe(change, id(me)))
    }

    @Test
    fun `nothing recognised is the group updated`() {
        assertEquals(listOf(Updated), GroupChangeLines.describe(DecryptedGroupChange(revision = 4), id(me)))
    }
}
