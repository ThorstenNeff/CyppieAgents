package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.model.ProjectsView
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.RuntimeState
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * E2E Journey J9 (CYP-256 .5b, extending CYP-255 .4b / CYP-247.4) — the **guarded full runtime-eviction
 * teardown + `runtimeState` field** over the REAL embedded platform, proving BOTH axes of the Option-A design:
 *
 *  - **RECLAIM (a runtime-created project):** beta's agents were added via `agentManagement.add` → persisted to
 *    the durable ProjectAgentStore. Beyond the HARD cap K=3, beta is suspended AND its runtime OBJECT is fully
 *    **EVICTED** (`of("beta") == null` — the memory reclaim .4b deferred; no held object ⇒ no leak of its
 *    per-project registries / connectorSessions). Re-entry **RE-MINTS** it end-to-end (getOrCreate → rehydrate
 *    from the store → resume → respawn) into a FRESH object with its session restored.
 *  - **GUARD (a config-seeded project):** alpha is the boot project — its roster comes from `config.agents`,
 *    NEVER written to the store. Evicting it would rehydrate an EMPTY runtime and silently lose "backend"
 *    (`resume` → "unknown agent"). So alpha is **NOT evicted**: when it is the LRU victim it is session-
 *    suspended with its OBJECT KEPT (`of("alpha") != null`), and re-entry resumes it from that kept object.
 *
 * Mutations (non-vacuous): drop the cap / LRU pick → nobody SUSPENDED → reds. Drop the .5b evict
 * ([RuntimeSuspensionPolicy.evictRuntime] / [RuntimeRegistry.evict]) → beta's object stays HELD (`of("beta")`
 * never nulls) → the RECLAIM await/assert reds. Drop the Option-A guard (`roster ⊆ store`) → alpha (config-
 * seeded) gets evicted → `of("alpha")` nulls at step 4 (GUARD assert reds) AND its re-mint loses "backend" so
 * step-5 respawn reds — **the silent-agent-loss proof**.
 */
class J9RuntimeStateE2eTest {

    // Four projects (K=3). alpha is active at boot (with a spawned `backend`); beta/gamma/delta are seeded.
    private fun fourProjects() = e2ePlatform(
        listOf(
            SeedProject("alpha", "Alpha", listOf(SeedAgent("po", Role.PO), SeedAgent("backend"))),
            SeedProject("beta", "Beta", listOf(SeedAgent("po", Role.PO), SeedAgent("worker"))),
            SeedProject("gamma", "Gamma", listOf(SeedAgent("po", Role.PO), SeedAgent("worker"))),
            SeedProject("delta", "Delta", listOf(SeedAgent("po", Role.PO), SeedAgent("worker"))),
        ),
    )

    @Test
    fun runtimeState_reportsHotBackgroundSuspended_capSuspendsLruAndReEntryResumes(): Unit = runBlocking {
        fourProjects().use { p ->
            // (1) Boot: alpha active = HOT; never-activated beta/gamma/delta = HOT (no indicator, fail-safe).
            p.runtimeStates().let { s ->
                assertEquals(RuntimeState.HOT, s["alpha"], "the active project is HOT")
                assertEquals(RuntimeState.HOT, s["beta"], "a never-activated project reads HOT (no indicator)")
                assertEquals(RuntimeState.HOT, s["gamma"])
                assertEquals(RuntimeState.HOT, s["delta"])
            }

            // (2) Switch to beta → beta HOT; START its worker so there is a RUNNING session to lose on suspend and
            // restore on re-entry (the reclaim signal; the harness seeds agents STOPPED). alpha (left) BACKGROUND.
            p.switchActive("beta")
            p.startAgent("worker")
            requireNotNull(p.booted.runtimeRegistry.of("beta")).let { rt ->
                withTimeout(20_000) { while (rt.connectorSessions.session("worker") == null) delay(20) }
            }
            p.runtimeStates().let { s ->
                assertEquals(RuntimeState.HOT, s["beta"])
                assertEquals(RuntimeState.BACKGROUND, s["alpha"], "the left-behind project runs in the background")
                assertEquals(RuntimeState.HOT, s["gamma"])
            }

            // (3) Switch to gamma → live = {alpha, beta, gamma} = K, still nothing suspended.
            p.switchActive("gamma")
            p.runtimeStates().let { s ->
                assertEquals(RuntimeState.HOT, s["gamma"])
                assertEquals(RuntimeState.BACKGROUND, s["alpha"])
                assertEquals(RuntimeState.BACKGROUND, s["beta"])
            }

            // (4) Switch to delta → live would be 4 > K=3 → the LRU background (alpha) is SUSPENDED. alpha is the
            // BOOT project (config-seeded roster, NOT in the durable store) → the Option-A guard KEEPS its object.
            p.switchActive("delta")
            p.runtimeStates().let { s ->
                assertEquals(RuntimeState.HOT, s["delta"])
                assertEquals(RuntimeState.SUSPENDED, s["alpha"], "the least-recently-hot background is suspended beyond K")
                assertEquals(RuntimeState.BACKGROUND, s["beta"])
                assertEquals(RuntimeState.BACKGROUND, s["gamma"])
            }
            // GUARD AXIS: a config-seeded project is session-suspended but its runtime object is NOT evicted —
            // evicting it would rehydrate an empty runtime and silently lose "backend" (not in the store). Its
            // session is really killed (suspend runs regardless of the evict guard); the OBJECT is kept so re-
            // entry resumes from it. Await the async suspend to settle, then assert the object survives.
            val alphaRt = requireNotNull(p.booted.runtimeRegistry.of("alpha"))
            withTimeout(20_000) { while (alphaRt.connectorSessions.session("backend") != null) delay(20) }
            assertNull(alphaRt.connectorSessions.session("backend"), "alpha's session is killed on suspension")
            assertNotNull(p.booted.runtimeRegistry.of("alpha"), "GUARD: the config-seeded BOOT project is NOT evicted (object kept) — else re-mint would lose its unpersisted roster")

            // (5) Switch BACK to alpha → resumes HOT from its KEPT object (+ session respawned); now beta is the
            // LRU. beta is a RUNTIME-CREATED project (agents persisted via agentManagement.add) → it IS evicted.
            p.switchActive("alpha")
            p.runtimeStates().let { s ->
                assertEquals(RuntimeState.HOT, s["alpha"], "re-entry resumes the guarded project from its kept object → HOT")
                assertEquals(RuntimeState.SUSPENDED, s["beta"], "the new LRU (a runtime-created project) is suspended on re-entry")
            }
            withTimeout(20_000) { while (alphaRt.connectorSessions.session("backend") == null) delay(20) }
            assertNotNull(alphaRt.connectorSessions.session("backend"), "alpha's session is respawned on re-entry from the kept object")
            // RECLAIM AXIS (evict): beta is fully store-backed → SUSPENDED + its runtime OBJECT fully EVICTED.
            // of("beta")==null proves the memory reclaim (a held object would keep beta's registries alive). Await
            // the async suspend+evict; a held object never nulls → reds under a mutation that drops the evict.
            withTimeout(20_000) { while (p.booted.runtimeRegistry.of("beta") != null) delay(20) }
            assertNull(p.booted.runtimeRegistry.of("beta"), "RECLAIM: a store-backed project is FULLY EVICTED beyond K (.5b memory reclaim) — no held object, no leak")

            // (6) Switch BACK to beta → re-entry RE-MINTS it end-to-end (getOrCreate → rehydrate the roster from
            // the durable store → resume → respawn) into a FRESH object with its session restored — proving the
            // evicted project is losslessly reconstructable BECAUSE its agents were persisted (the guard's premise).
            p.switchActive("beta")
            assertEquals(RuntimeState.HOT, p.runtimeStates()["beta"], "re-entry re-mints + resumes the evicted store-backed project → HOT")
            val betaRt = requireNotNull(p.booted.runtimeRegistry.of("beta")) { "re-entry must RE-MINT the evicted runtime object" }
            withTimeout(20_000) { while (betaRt.connectorSessions.session("worker") == null) delay(20) }
            assertNotNull(betaRt.connectorSessions.session("worker"), "beta's worker session is respawned on re-entry into the re-minted runtime (rehydrated from the store)")
        }
    }

    /** GET /api/projects (cold operator read) → projectId → its server-derived runtimeState. */
    private suspend fun E2ePlatform.runtimeStates(): Map<String, RuntimeState> =
        asOperator().use { it.get("$baseUrl/api/projects").body<ProjectsView>() }
            .projects.associate { it.id to it.runtimeState }

    /** Start an agent in the ACTIVE project (CYP-73 lifecycle) so it has a RUNNING session — the harness seeds
     *  agents STOPPED, and the reclaim axis needs a live session to lose on suspend and restore on re-entry. */
    private suspend fun E2ePlatform.startAgent(id: String) =
        asOperator().use { it.post("$baseUrl/api/agents/$id/start") }
}
