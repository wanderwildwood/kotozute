package com.wanderwildwood.kotozute.common.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionMenuPlaceRestTest {

    @Test
    fun aListThatFitsBelowGoesBelow() {
        assertEquals(500 to 200, SelectionMenu.placeRest(200, below = 500, roomBelow = 300, aboveEnd = 300, roomAbove = 250))
    }

    @Test
    fun aListThatFitsOnlyAboveEndsWhereTheBarBegins() {
        assertEquals(50 to 200, SelectionMenu.placeRest(200, below = 650, roomBelow = 150, aboveEnd = 250, roomAbove = 202))
    }

    @Test
    fun aListThatFitsNeitherSideTakesTheRoomierSideAndNeverReachesTheBar() {
        // The case seen on a phone: selection mid-screen, 362 px of list, 259 px above, 150 below.
        val (top, height) = SelectionMenu.placeRest(362, below = 650, roomBelow = 150, aboveEnd = 307, roomAbove = 259)
        assertEquals(48, top)
        assertEquals(259, height)
        assert(top + height <= 307)
    }

    @Test
    fun withMoreRoomBelowItIsCutBelow() {
        assertEquals(400 to 300, SelectionMenu.placeRest(500, below = 400, roomBelow = 300, aboveEnd = 150, roomAbove = 100))
    }
}
