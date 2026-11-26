package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The "update due" notice counts from [SignalNetworkConfig.LIBRARIES_UPDATED], so that date
 * has to be the one `signal-service/README.md` names for the copy actually in the tree.
 * A re-copy that forgets the constant fails here instead of warning two months early or late.
 */
class SignalLibrariesDateTest {

    @Test
    fun `the date matches the copy signal-service names`() {
        val readme = listOf("../signal-service/README.md", "signal-service/README.md")
            .map(::File).first { it.isFile }.readText()
        val named = Regex("""\(\s*(\d{4}-\d{2}-\d{2}), the commit of release tag""").find(readme)
            ?.groupValues?.get(1)
            ?: error("signal-service/README.md no longer says which commit it was copied from")
        assertEquals(named, SignalNetworkConfig.LIBRARIES_UPDATED.toString())
    }

    @Test
    fun `due only after the sixty days have passed`() {
        val updated = SignalNetworkConfig.LIBRARIES_UPDATED
        assertFalse(SignalNetworkConfig.updateDue(updated))
        assertFalse(SignalNetworkConfig.updateDue(updated.plusDays(60)))
        assertTrue(SignalNetworkConfig.updateDue(updated.plusDays(61)))
    }
}
