package com.wanderwildwood.kotozute.common.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The discard path writes two preferences by name, because it runs before the object that
 * owns them can be built. A rename on the other side would not fail to compile -- it would
 * silently leave the Signal cursor pointing past a database that no longer holds anything,
 * and the rail would stay empty with nothing to show why.
 */
class RealmEncryptionPrefNamesTest {

    @Test
    fun `the names the discard path writes are the names Preferences reads`() {
        // Mirrors Preferences.signalCursor / .signalBridgeInstance. Change one, change both.
        assertEquals("signalCursor", RealmEncryption.PREF_SIGNAL_CURSOR)
        assertEquals("signalBridgeInstance", RealmEncryption.PREF_SIGNAL_BRIDGE_INSTANCE)
    }
}
