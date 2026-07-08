package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.model.ProjectsView
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.RuntimeState
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * E2E Journey J9 (CYP-255 .4b / CYP-247.4) — the **session-suspension teardown + `runtimeState` field** over
 * the REAL embedded platform. The ratified L client-UX "resource honesty": `GET /api/projects` reports each
 * project's live runtime state (HOT / BACKGROUND / SUSPENDED), and beyond the HARD cap K=3 the least-recently-
 * hot BACKGROUND project is really session-suspended (its `claude` processes killed, session ids persisted),
 * then resumed on re-entry. Proves the field transitions over real `POST /api/projects/switch` AND that the
 * suspend really kills the session (not just a label) + re-entry brings it back.
 *
 * Mutation: drop the cap enforcement (or the LRU pick) → `alpha` is never SUSPENDED at step 4 → this reds.
 */
class J9RuntimeStateE2eTest {

    // Four projects, EXPLICIT cap K=3. CYP-247 S3: the production DEFAULT is now cap=1 (teardown-on-switch, so a
    // left project is SUSPENDED immediately, never BACKGROUND). This journey exercises the cap>1 background-live
    // state machine as a MODE, so it sets the cap explicitly; it also confirms the S3 switch-reorder does NOT
    // drain the outgoing when cap>1 (a left project stays live/BACKGROUND, not torn down).
    private fun fourProjects() = e2ePlatform(
        listOf(
            SeedProject("alpha", "Alpha", listOf(SeedAgent("po", Role.PO), SeedAgent("backend"))),
            SeedProject("beta", "Beta", listOf(SeedAgent("po", Role.PO), SeedAgent("worker"))),
            SeedProject("gamma", "Gamma", listOf(SeedAgent("po", Role.PO), SeedAgent("worker"))),
            SeedProject("delta", "Delta", listOf(SeedAgent("po", Role.PO), SeedAgent("worker"))),
        ),
        runtimeSuspensionCap = 3,
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

            // (2) Switch to beta → beta HOT, alpha (just left) BACKGROUND. gamma/delta still never-activated.
            p.switchActive("beta")
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

            // (4) Switch to delta → live would be 4 > K=3 → the LRU background (alpha) is SUSPENDED.
            p.switchActive("delta")
            p.runtimeStates().let { s ->
                assertEquals(RuntimeState.HOT, s["delta"])
                assertEquals(RuntimeState.SUSPENDED, s["alpha"], "the least-recently-hot background is suspended beyond K")
                assertEquals(RuntimeState.BACKGROUND, s["beta"])
                assertEquals(RuntimeState.BACKGROUND, s["gamma"])
            }
            // ...and the suspend REALLY killed alpha's live session (not just a label) — persist-kill.
            val alphaRt = requireNotNull(p.booted.runtimeRegistry.of("alpha"))
            withTimeout(5_000) {
                while (alphaRt.connectorSessions.session("backend") != null) delay(20)
            }
            assertNull(alphaRt.connectorSessions.session("backend"), "alpha's backend process was killed on suspension")

            // (5) Switch BACK to alpha → resumed (HOT) + its session respawned (--resume); beta is now the LRU → SUSPENDED.
            p.switchActive("alpha")
            p.runtimeStates().let { s ->
                assertEquals(RuntimeState.HOT, s["alpha"], "re-entry resumes the suspended project → HOT")
                assertEquals(RuntimeState.SUSPENDED, s["beta"], "the new LRU is suspended on re-entry")
            }
            withTimeout(5_000) {
                while (alphaRt.connectorSessions.session("backend") == null) delay(20)
            }
            assertNotNull(alphaRt.connectorSessions.session("backend"), "alpha's backend session is respawned on re-entry")
        }
    }

    /** GET /api/projects (cold operator read) → projectId → its server-derived runtimeState. */
    private suspend fun E2ePlatform.runtimeStates(): Map<String, RuntimeState> =
        asOperator().use { it.get("$baseUrl/api/projects").body<ProjectsView>() }
            .projects.associate { it.id to it.runtimeState }
}
