package com.wanderwildwood.kotozute.signalstore

import com.wanderwildwood.kotozute.signal.BridgeMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A decrypted message written down between its envelope going and the message database
 * having it.
 *
 * ⚠ That copy is the only one. A field the codec drops is not a formatting slip: the message
 * filed from it is missing that field for good -- its timer, its quote, its group. So every
 * field is set to something other than its default here, and compared.
 */
class UnfiledMessageTest {

    private val full = BridgeMessage(
        id = "aci-1:1790000000000",
        threadKey = "group:abc",
        ts = 1_790_000_000_000L,
        senderUuid = "aci-1",
        senderNumber = "+15550100",
        outgoing = true,
        body = "line one\nline two — \"quoted\" 言伝 🍄",
        groupId = "abc",
        quoteTs = 1_789_999_999_999L,
        read = true,
        source = "live",
        attachmentsJson = """[{"id":"x","contentType":"image/jpeg"}]""",
        expiresAt = 1_790_000_600_000L,
        expiresInSeconds = 600,
        viewOnce = true,
        reactionEmoji = "👍",
        reactionTarget = "aci-2:1789",
        reactionRemove = true,
        groupMasterKey = ByteArray(32) { it.toByte() },
        revisionTs = 1_790_000_000_555L
    )

    @Test
    fun `every field comes back`() {
        val back = UnfiledMessage.decode(UnfiledMessage.encode(full))
        // The key is compared by content; a data class compares a ByteArray by identity.
        assertArrayEquals(full.groupMasterKey, back.groupMasterKey)
        assertEquals(full.copy(groupMasterKey = null), back.copy(groupMasterKey = null))
    }

    @Test
    fun `a message with no group key comes back without one`() {
        val plain = full.copy(groupMasterKey = null, groupId = "", threadKey = "direct:aci-1")
        val back = UnfiledMessage.decode(UnfiledMessage.encode(plain))
        assertNull(back.groupMasterKey)
        assertEquals(plain, back)
    }
}
