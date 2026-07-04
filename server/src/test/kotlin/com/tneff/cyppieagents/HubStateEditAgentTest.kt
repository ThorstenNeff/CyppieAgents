package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-210 (Test defense-in-depth) — [HubState.editAgent] applies blank→PRESERVE itself, so a DIRECT caller
 * (bypassing the route's `ifBlank→null` normalize) can't clear name/color with a blank string where the
 * durable overlay `store.put` (ifBlank-robust) would preserve it. Parity between the in-memory update and
 * the durable overlay, regardless of who calls.
 *
 * Mutation (teeth): revert to `name = name ?: cur.name` → the direct blank call clears the name → reddens.
 */
class HubStateEditAgentTest {

    private fun state() = HubState(
        listOf(Agent("backend", "BE", Role.WORKER, "backend")),
        emptyList(),
        emptyList(),
    )

    @Test
    fun editAgent_directBlank_preserves_notClear() {
        val s = state()
        s.editAgent("backend", name = "Bob", color = "#111111")
        val after = s.editAgent("backend", name = "", color = "#222222") // direct blank — must PRESERVE the name
        assertEquals("Bob", after!!.name, "a direct blank name must be preserved, not cleared (parity with the overlay store)")
        assertEquals("#222222", after.color, "a non-blank color still updates")
        // and a blank color preserves too
        assertEquals("#222222", s.editAgent("backend", color = "")!!.color, "a direct blank color preserves")
    }

    @Test
    fun editAgent_unknownId_returnsNull() {
        assertNull(state().editAgent("nope", name = "x"))
    }
}
