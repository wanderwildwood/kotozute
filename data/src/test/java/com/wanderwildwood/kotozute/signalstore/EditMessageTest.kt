package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which message an edit lands on.
 *
 * A row's identity here is its author and its timestamp, so an edit that reuses the
 * *original's* timestamp resolves to the same id and rewrites that row. Using the edit's own
 * timestamp would add a second bubble saying nearly the same thing, leave the original
 * standing, and put the copy at the end of the thread instead of where the message actually
 * sits.
 *
 * The wiring that picks the target lives in `ContentNormalizer.normalize` and needs a protobuf
 * to exercise; this pins the identity rule it depends on.
 */
class EditMessageTest {

    private val author = "4f6e2a8c-1b3d-4e5f-9a7b-2c8d6e0f1a3b"

    @Test
    fun `an edit reusing the original timestamp is the same row`() {
        val original = ContentNormalizer.messageIdFor(author, "", 1789140526501)
        val edited = ContentNormalizer.messageIdFor(author, "", 1789140526501)
        assertEquals(original, edited)
    }

    @Test
    fun `an edit stamped with its own time would be a different row`() {
        // The bug this rule avoids, stated so it cannot quietly come back.
        val original = ContentNormalizer.messageIdFor(author, "", 1789140526501)
        val ifWeUsedTheEditsOwnTime = ContentNormalizer.messageIdFor(author, "", 1789140999999)
        assert(original != ifWeUsedTheEditsOwnTime)
    }

    @Test
    fun `identity falls back to the number when there is no service id`() {
        assertEquals("+18285550123:99", ContentNormalizer.messageIdFor("", "+18285550123", 99))
    }
}
