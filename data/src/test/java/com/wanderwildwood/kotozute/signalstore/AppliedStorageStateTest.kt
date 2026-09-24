package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Test

class AppliedStorageStateTest {

    private fun state(key: String, id: String, archived: Boolean = true) =
        SignalStorageService.ConversationState(key, muted = false, archived = archived, recordId = id)

    private val everywhere: (String) -> Boolean = { true }

    @Test
    fun `an unchanged record is not applied again`() {
        // The forum #47 case: archived in Signal, unarchived here, and the next read carries
        // the same record id saying archived.
        val plan = AppliedStorageState.plan(listOf(state("direct:a", "id1")), setOf("id1"), everywhere)
        assertEquals(emptyList<SignalStorageService.ConversationState>(), plan.apply)
        assertEquals(setOf("id1"), plan.applied)
    }

    @Test
    fun `a changed record is applied`() {
        // Control: the same conversation under a new id is the account saying something new.
        val plan = AppliedStorageState.plan(listOf(state("direct:a", "id2")), setOf("id1"), everywhere)
        assertEquals(listOf("id2"), plan.apply.map { it.recordId })
        assertEquals(setOf("id2"), plan.applied)
    }

    @Test
    fun `nothing remembered applies everything once`() {
        val states = listOf(state("direct:a", "id1"), state("group:g", "id2"))
        val plan = AppliedStorageState.plan(states, emptySet(), everywhere)
        assertEquals(states, plan.apply)
    }

    @Test
    fun `a conversation not on the phone is neither applied nor remembered`() {
        val plan = AppliedStorageState.plan(listOf(state("direct:a", "id1")), emptySet()) { false }
        assertEquals(emptyList<SignalStorageService.ConversationState>(), plan.apply)
        assertEquals(emptySet<String>(), plan.applied)
        // ...so once it exists, the same record lands.
        val later = AppliedStorageState.plan(listOf(state("direct:a", "id1")), plan.applied, everywhere)
        assertEquals(listOf("id1"), later.apply.map { it.recordId })
    }
}
