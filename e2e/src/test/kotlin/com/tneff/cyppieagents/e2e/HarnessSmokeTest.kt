package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ProjectsView
import com.tneff.cyppieagents.model.Role
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-106 — harness smoke acceptance (§7): boot 2 projects over the REAL `installPlatform`, then prove
 * (a) the operator sees BOTH projects via the real `/api/projects`, and (b) an agent sees ONLY its
 * project's channels via the real `/api/channels` (the active project's, not the other's). Green before
 * J1/J2 build on the harness.
 */
class HarnessSmokeTest {

    @Test
    fun smoke_twoProjects_operatorSeesBoth_agentSeesOnlyItsChannels() = runBlocking {
        e2ePlatform(
            listOf(
                SeedProject("alpha", "Alpha", listOf(SeedAgent("po", Role.PO), SeedAgent("frontend"))),
                SeedProject("beta", "Beta", listOf(SeedAgent("backend"))),
            ),
        ).use { p ->
            // (a) operator sees BOTH projects; alpha is active (first).
            val view: ProjectsView = p.asOperator().use { it.get("${p.baseUrl}/api/projects").body() }
            assertEquals(setOf("alpha", "beta"), view.projects.map { it.id }.toSet(), "operator sees both seeded projects")
            assertEquals("alpha", view.activeProjectId, "first project is active")

            // (b) the frontend agent (active = alpha) sees ONLY alpha's channel — beta's is not visible.
            val channels: List<Channel> = p.asAgent("frontend").use { it.get("${p.baseUrl}/api/channels").body() }
            assertEquals(listOf("po-frontend"), channels.map { it.id }, "agent sees only its active project's channel")
            assertTrue(channels.none { it.id == "po-backend" }, "the other project's channel is not visible (scoped)")

            // (c) PROJECT-SCOPE PARITY (Reviewer): the OPERATOR is a member of EVERY channel, so membership
            // can't hide `po-backend` — ONLY ProjectScope can. As operator with active=alpha the list must
            // still be just [po-frontend]; under `ProjectScope.permits → true` the operator would see
            // `po-backend` too, so this reddens. (The frontend probe above only proves membership isolation.)
            val opChannels: List<Channel> = p.asOperator().use { it.get("${p.baseUrl}/api/channels").body() }
            // CYP-787: the active project (alpha) is boot-seeded → it has the PO's op-po spoke (operator is a member).
            // The project-scope point stands: po-backend (beta) is still NOT here — only membership+scope, and op-po is alpha's.
            assertEquals(listOf("po-frontend", "op-po"), opChannels.map { it.id }, "operator sees the active project's channels (incl. op-po) → project-scope, not the foreign po-backend")
        }
    }
}
