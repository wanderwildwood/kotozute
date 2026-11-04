package com.wanderwildwood.kotozute.common.util

import com.wanderwildwood.kotozute.common.util.TextViewStyler.Companion.HOUSE_TYPE_FLOOR_SP
import com.wanderwildwood.kotozute.common.util.TextViewStyler.Companion.SIZE_DIALOG
import com.wanderwildwood.kotozute.common.util.TextViewStyler.Companion.SIZE_EMOJI
import com.wanderwildwood.kotozute.common.util.TextViewStyler.Companion.SIZE_PRIMARY
import com.wanderwildwood.kotozute.common.util.TextViewStyler.Companion.SIZE_SECONDARY
import com.wanderwildwood.kotozute.common.util.TextViewStyler.Companion.SIZE_TERTIARY
import com.wanderwildwood.kotozute.common.util.TextViewStyler.Companion.SIZE_TOOLBAR
import com.wanderwildwood.kotozute.util.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nothing smaller than 16 sp, at any of the reader's text sizes (Mudita's review, 4 Nov 2026). */
class TextSizeFloorTest {

    private val roles = listOf(SIZE_PRIMARY, SIZE_SECONDARY, SIZE_TERTIARY, SIZE_TOOLBAR, SIZE_DIALOG, SIZE_EMOJI)
    private val prefs = listOf(
        Preferences.TEXT_SIZE_SMALL, Preferences.TEXT_SIZE_NORMAL, Preferences.TEXT_SIZE_LARGE,
        Preferences.TEXT_SIZE_LARGER, Preferences.TEXT_SIZE_SUPER, 99,
    )

    @Test fun floorIsSixteen() = assertEquals(16f, HOUSE_TYPE_FLOOR_SP)

    @Test fun noRoleAtAnySettingIsUnderTheFloor() {
        for (role in roles) for (pref in prefs) {
            val sp = TextViewStyler.sizeFor(role, pref)!!
            assertTrue("role $role at $pref is $sp sp", sp >= HOUSE_TYPE_FLOOR_SP)
        }
    }

    @Test fun sixteenSpIsAtLeast24PxOnTheKompakt() {
        // A line of Lato is 1.2 times its size; the Kompakt is 213 dpi.
        assertTrue(HOUSE_TYPE_FLOOR_SP * 213f / 160f * 1.2f >= 24f)
    }

    @Test fun theNormalSettingKeepsItsLargerRoles() {
        assertEquals(18f, TextViewStyler.sizeFor(SIZE_PRIMARY, Preferences.TEXT_SIZE_NORMAL))
        assertEquals(16f, TextViewStyler.sizeFor(SIZE_SECONDARY, Preferences.TEXT_SIZE_NORMAL))
        assertEquals(16f, TextViewStyler.sizeFor(SIZE_TERTIARY, Preferences.TEXT_SIZE_NORMAL))
        assertEquals(24f, TextViewStyler.sizeFor(SIZE_TOOLBAR, Preferences.TEXT_SIZE_NORMAL))
    }

    @Test fun anUnknownRoleIsLeftAlone() = assertNull(TextViewStyler.sizeFor(-1, Preferences.TEXT_SIZE_NORMAL))
}
