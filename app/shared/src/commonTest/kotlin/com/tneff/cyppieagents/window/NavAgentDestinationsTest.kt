package com.tneff.cyppieagents.window

import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-896 (S3) — [navAgentDestinations]: the rail's agent destinations are ordered **PO → PRODUCT_LEAD → Worker**
 * (CYP-98's three Role values), each **present-iff** such an agent exists, and **PRODUCT_LEAD is DISTINCT** (never
 * folded into the Worker bucket, never collapsed with PO, never dropped).
 */
class NavAgentDestinationsTest {

    private fun agent(id: String, role: Role) = Agent(id, id.uppercase(), role, id)

    @Test
    fun ordersPo_thenProductLead_thenWorkers_inRosterOrder() {
        val dests = navAgentDestinations(
            listOf(agent("w2", Role.WORKER), agent("po", Role.PO), agent("pl", Role.PRODUCT_LEAD), agent("w1", Role.WORKER)),
        )
        assertEquals(listOf("po", "pl", "w2", "w1"), dests.map { it.agentId })
        assertEquals(listOf(Role.PO, Role.PRODUCT_LEAD, Role.WORKER, Role.WORKER), dests.map { it.role })
    }

    @Test
    fun presentIff_noAgentOfARole_noRow_severalOfARole_allRows() {
        // No PRODUCT_LEAD agent → no PRODUCT_LEAD destination (no dead row).
        val noPl = navAgentDestinations(listOf(agent("po", Role.PO), agent("w", Role.WORKER)))
        assertEquals(0, noPl.count { it.role == Role.PRODUCT_LEAD })
        // Several workers → all of them.
        val manyW = navAgentDestinations(listOf(agent("w1", Role.WORKER), agent("w2", Role.WORKER), agent("w3", Role.WORKER)))
        assertEquals(listOf("w1", "w2", "w3"), manyW.map { it.agentId })
    }

    @Test
    fun productLead_isDistinct_neverFoldedIntoWorker_norCollapsedWithPo_norDropped() {
        // The present-iff-PRODUCT_LEAD tooth. MUT: drop/fold the PRODUCT_LEAD bucket (→ WORKER) → no distinct PL
        // destination → single{} throws → reds.
        val dests = navAgentDestinations(listOf(agent("pl", Role.PRODUCT_LEAD), agent("w", Role.WORKER), agent("po", Role.PO)))
        val pl = dests.single { it.role == Role.PRODUCT_LEAD }
        assertEquals("pl", pl.agentId)
        // …and it sits BETWEEN po and the workers (distinct middle group), never collapsed with either.
        assertEquals(listOf(Role.PO, Role.PRODUCT_LEAD, Role.WORKER), dests.map { it.role })
    }
}
