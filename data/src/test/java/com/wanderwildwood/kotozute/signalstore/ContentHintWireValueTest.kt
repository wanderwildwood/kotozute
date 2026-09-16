package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Test
import org.whispersystems.signalservice.api.crypto.ContentHint

/**
 * The numbers the resend log stores for a content hint.
 *
 * A resend used to assert `RESENDABLE` whatever the original said. The hint tells a recipient
 * what to do when they cannot read a message -- show an error now, show nothing and wait for a
 * resend, or need no error at all -- so replaying the wrong one tells somebody to wait for
 * something after first telling them not to worry about it.
 *
 * Pinned here because the value goes into a database column and outlives the process. If the
 * library ever renumbers them, every row already written means something different, and nothing
 * else in this app would notice.
 */
class ContentHintWireValueTest {

    @Test
    fun `the wire values are what the column was defined against`() {
        // These numbers were originally read out of the prebuilt fork's bytecode, because its
        // source could not be read. The source is in the tree now -- `ContentHint.java:8` --
        // and it does not spell the numbers out at all: each constant takes
        // `UnidentifiedSenderMessageContent.CONTENT_HINT_*`, which lives in libsignal, the one
        // dependency still arriving as a published binary. So this assertion is still the only
        // place the three values are written down, and still worth having.
        assertEquals(0, ContentHint.DEFAULT.type)
        assertEquals(1, ContentHint.RESENDABLE.type)
        assertEquals(2, ContentHint.IMPLICIT.type)
    }

    @Test
    fun `the column default is RESENDABLE`() {
        // v31 adds `content_hint INTEGER NOT NULL DEFAULT 1`. Every row written before it was
        // sent RESENDABLE except a group update, and RESENDABLE is exactly what the resend
        // path already assumed -- so the migration changes nothing already recorded.
        assertEquals(1, ContentHint.RESENDABLE.type)
    }

    @Test
    fun `a stored value comes back as the hint it was`() {
        // The round trip the resend depends on.
        for (hint in listOf(ContentHint.DEFAULT, ContentHint.RESENDABLE, ContentHint.IMPLICIT)) {
            assertEquals(hint, ContentHint.fromType(hint.type))
        }
    }
}
