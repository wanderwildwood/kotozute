package com.wanderwildwood.kotozute.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The list and the notification ask the same question of a text: is it news? A reaction that
 * the list leaves out must not be announced either, or the launcher marks the icon for a
 * conversation that reads as all done.
 */
class ReactionIsNewsTest {

    @Test
    fun anOrdinaryTextIsAlwaysNews() {
        assertTrue(isShownAsNews(isReaction = false, fromMe = false, stillStands = false))
        assertTrue(isShownAsNews(isReaction = false, fromMe = true, stillStands = false))
    }

    @Test
    fun someoneElsesStandingReactionIsNews() {
        assertTrue(isShownAsNews(isReaction = true, fromMe = false, stillStands = true))
    }

    @Test
    fun aRemovalOrAReplacedReactionIsNot() {
        // "Removed a heart from …" leaves no reaction record behind, and neither does one
        // since replaced by the same sender's next.
        assertFalse(isShownAsNews(isReaction = true, fromMe = false, stillStands = false))
    }

    @Test
    fun ourOwnReactionIsNot() {
        assertFalse(isShownAsNews(isReaction = true, fromMe = true, stillStands = true))
    }
}
