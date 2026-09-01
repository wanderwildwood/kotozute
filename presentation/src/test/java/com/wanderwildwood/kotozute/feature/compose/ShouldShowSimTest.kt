package com.wanderwildwood.kotozute.feature.compose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This behaviour only exists on a phone with more than one SIM. Neither the emulator nor a
 * single-SIM device ever runs it, so the rule is tested here rather than trusted.
 */
class ShouldShowSimTest {

    private fun show(
        simCount: Int = 2,
        subId: Int = 1,
        previousSubId: Int? = 1,
        isNewest: Boolean = false,
        hasSubscription: Boolean = true
    ) = shouldShowSim(hasSubscription, simCount, subId, previousSubId, isNewest)

    @Test
    fun `never marked on a single-SIM phone`() {
        assertFalse(show(simCount = 1, isNewest = true))
        assertFalse(show(simCount = 1, previousSubId = 2))
        assertFalse(show(simCount = 0, isNewest = true))
    }

    @Test
    fun `not marked when the subscription is unknown`() {
        // Nothing sensible to print, so printing an empty marker would be worse.
        assertFalse(show(hasSubscription = false, isNewest = true))
        assertFalse(show(hasSubscription = false, previousSubId = 2))
    }

    @Test
    fun `marked where the SIM changes`() {
        assertTrue(show(subId = 2, previousSubId = 1))
    }

    @Test
    fun `marked on the first message, which has nothing before it`() {
        assertTrue(show(previousSubId = null))
    }

    @Test
    fun `marked on the newest message even when the SIM never changed`() {
        // The case the change was made for: a thread carried entirely on one SIM used to
        // be labelled only at the very top, where nobody looking at recent messages sees it.
        assertTrue(show(subId = 1, previousSubId = 1, isNewest = true))
    }

    @Test
    fun `quiet in the middle of an unchanging run`() {
        assertFalse(show(subId = 1, previousSubId = 1, isNewest = false))
    }

    @Test
    fun `a thread on one SIM is marked once, at the end`() {
        val subIds = listOf(1, 1, 1, 1)
        val marked = subIds.mapIndexed { i, id ->
            show(
                subId = id,
                previousSubId = if (i == 0) null else subIds[i - 1],
                isNewest = i == subIds.lastIndex
            )
        }
        // The first also marks -- nothing precedes it -- but the one that matters is the last.
        assertTrue("the newest must be marked", marked.last())
        assertFalse("the middle must stay quiet", marked[1])
        assertFalse("the middle must stay quiet", marked[2])
    }

    @Test
    fun `a thread that switches is marked at each switch and at the end`() {
        val subIds = listOf(1, 1, 2, 2, 1)
        val marked = subIds.mapIndexed { i, id ->
            show(
                subId = id,
                previousSubId = if (i == 0) null else subIds[i - 1],
                isNewest = i == subIds.lastIndex
            )
        }
        assertTrue(marked[0])   // first
        assertFalse(marked[1])  // unchanged
        assertTrue(marked[2])   // switched to 2
        assertFalse(marked[3])  // unchanged
        assertTrue(marked[4])   // switched back, and newest
    }
}
