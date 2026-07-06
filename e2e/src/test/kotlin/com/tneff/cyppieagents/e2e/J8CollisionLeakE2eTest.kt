package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Role
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * E2E Journey J8 (CYP-255 .4b) — **the collision-leak money-tooth: THE merge gate for the per-project
 * runtime de-singletonization.** Two projects each declare an agent id `backend`. Before .4 they would have
 * shared ONE agentId-keyed session map / lifecycle / config registry (the singletons M/CYP-246 left), so the
 * two `backend`s would collide — a `/ws/agent` attach, a spawn, a stop could cross projects. This proves,
 * over the REAL embedded platform (real boot + real `POST /api/projects/switch` + real
 * `POST /api/agents/{id}/start` + real `GET /api/agents`), that after .4 each project resolves to a DISTINCT
 * runtime: separate sessions, separate `projects/<id>/backend` worktrees, separate lifecycle/config/caps —
 * no cross-project bleed.
 *
 * **Mutation (what makes it load-bearing):** revert the seed / the switch to reuse the boot runtime's shared
 * instances (the pre-.4 singleton), or key sessions by a bare agentId across projects, and the two projects'
 * `connectorSessions` / `backend` sessions become the SAME object → the `assertNotSame` assertions reden.
 * The CapabilityCeiling/ProjectScope egress teeth (① ② ③) cover the data-plane; this covers the runtime plane.
 */
class J8CollisionLeakE2eTest {

    /** Two fully-populated projects that BOTH contain an agent id `backend` (and a `po`) — the collision. */
    private fun collidingProjects() = e2ePlatform(
        listOf(
            SeedProject("alpha", "Alpha", listOf(SeedAgent("po", Role.PO), SeedAgent("backend"))),
            SeedProject("beta", "Beta", listOf(SeedAgent("po", Role.PO), SeedAgent("backend"))),
        ),
    )

    @Test
    fun collisionLeak_sameAgentIdInTwoProjects_distinctRuntimes_noSessionOrWorktreeBleed() = runBlocking {
        collidingProjects().use { p ->
            val reg = p.booted.runtimeRegistry
            val alphaRt = requireNotNull(reg.of("alpha")) { "alpha (boot) runtime is live" }
            val betaRt = requireNotNull(reg.of("beta")) { "beta runtime was minted on seed (via the real factory)" }

            // (1) DISTINCT per-project runtime instances — the de-singletonization itself. If the seed/switch
            // reused the boot runtime's shared instances (the pre-.4 singleton), these would be the SAME object.
            assertNotSame(alphaRt.connectorSessions, betaRt.connectorSessions, "each project has its OWN session map")
            assertNotSame(alphaRt.lifecycle, betaRt.lifecycle, "each project has its OWN lifecycle")
            assertNotSame(alphaRt.agentConfigs, betaRt.agentConfigs, "each project has its OWN agent-config registry")
            assertNotSame(alphaRt.capabilityRegistry, betaRt.capabilityRegistry, "each project has its OWN capability registry")

            // (2) DISTINCT worktree roots + on-disk `projects/<id>/backend` dirs (created by the real
            // ensureWorktree at boot-spawn for alpha and at add for beta) — no shared worktree collision.
            assertTrue(alphaRt.worktrees.worktreesRoot.path.endsWith("projects/alpha"), "alpha root = projects/alpha")
            assertTrue(betaRt.worktrees.worktreesRoot.path.endsWith("projects/beta"), "beta root = projects/beta")
            val alphaWt = File(alphaRt.worktrees.worktreesRoot, "backend")
            val betaWt = File(betaRt.worktrees.worktreesRoot, "backend")
            assertTrue(alphaWt.isDirectory, "alpha's backend worktree exists on disk")
            assertTrue(betaWt.isDirectory, "beta's backend worktree exists on disk")
            assertFalse(alphaWt.canonicalPath == betaWt.canonicalPath, "the two `backend` worktrees are SEPARATE dirs")

            // (3) DISTINCT sessions for the SAME agent id `backend`. alpha's is live from boot; start beta's
            // through the REAL lifecycle endpoint while beta is active (→ active().lifecycle = beta's). Neither
            // session is the other — a bare agentId no longer resolves one shared session across projects.
            val alphaBackend = alphaRt.connectorSessions.session("backend")
            assertNotNull(alphaBackend, "alpha's backend spawned at boot (its own session)")

            p.switchActive("beta")
            p.asOperator().use { it.post("${p.baseUrl}/api/agents/backend/start") } // real start in beta's runtime
            val betaBackend = betaRt.connectorSessions.session("backend")
            assertNotNull(betaBackend, "beta's backend started in beta's runtime (its own session)")
            assertNotSame(alphaBackend, betaBackend, "two projects' `backend` sessions are SEPARATE — no shared-map collision")

            // and neither project's session map contains the other's session (no cross-attach)
            assertFalse(betaRt.connectorSessions.session("backend") === alphaBackend, "beta's map never holds alpha's session")
            assertFalse(alphaRt.connectorSessions.session("backend") === betaBackend, "alpha's map never holds beta's session")

            // (4) No agent-set bleed over the REAL cold roster read: active=beta shows beta's OWN roster, and
            // the colliding id resolves to beta's — alpha's `backend` is stashed, not carried across.
            val betaRoster = p.asOperator().use { it.get("${p.baseUrl}/api/agents").body<List<Agent>>() }.map { it.id }.toSet()
            assertEquals(setOf("po", "backend"), betaRoster, "active=beta → beta's roster (its own po + backend)")

            // (5) switch back → alpha's runtime + roster intact; beta's start did not touch alpha's backend session.
            p.switchActive("alpha")
            assertEquals(setOf("po", "backend"), p.asOperator().use { it.get("${p.baseUrl}/api/agents").body<List<Agent>>() }.map { it.id }.toSet())
            assertTrue(alphaRt.connectorSessions.session("backend") === alphaBackend, "alpha's backend session is unchanged by beta's lifecycle op")
        }
    }
}
