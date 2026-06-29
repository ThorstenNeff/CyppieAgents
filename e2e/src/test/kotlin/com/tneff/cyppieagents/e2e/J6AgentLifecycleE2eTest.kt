package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.AgentRunStateEvent
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.Role
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E2E Journey J6 (CYP-109) — Agent lifecycle (start/stop/restart) + the content-free status feed, over
 * the REAL platform. Controls are operator-gated/fail-closed (agent→403, no-token→401, unknown→404,
 * double-start→409); the `/api/agents` status display + `/ws/lifecycle` are participant-readable.
 *
 * DEFERRED (harness): the `spawn_failed`/ERROR (failed-spawn → no session) axis needs a spawner that
 * fails; the CYP-106 FakeSpawner always succeeds, so that axis is unit-covered (LifecycleManagerTest).
 */
class J6AgentLifecycleE2eTest {

    private fun platform() = e2ePlatform(
        listOf(SeedProject("alpha", "A", listOf(SeedAgent("po", Role.PO), SeedAgent("frontend")))),
    )

    @Test
    fun lifecycle_stop_start_restart_transitions() = runBlocking {
        platform().use { p ->
            p.asOperator().use { c ->
                // normalize → STOPPED (stop is idempotent on state)
                assertEquals(AgentRunState.STOPPED, c.post("${p.baseUrl}/api/agents/frontend/stop").body<AgentRunStateEvent>().runState)
                // STOPPED → start → RUNNING
                assertEquals(AgentRunState.RUNNING, c.post("${p.baseUrl}/api/agents/frontend/start").body<AgentRunStateEvent>().runState)
                // already RUNNING → 409 already_running
                val again = c.post("${p.baseUrl}/api/agents/frontend/start")
                assertEquals(HttpStatusCode.Conflict, again.status)
                assertEquals("already_running", again.body<ApiErrorBody>().error.code)
                // restart → RUNNING
                assertEquals(AgentRunState.RUNNING, c.post("${p.baseUrl}/api/agents/frontend/restart").body<AgentRunStateEvent>().runState)
            }
        }
    }

    @Test
    fun lifecycle_controls_operatorGated() = runBlocking {
        platform().use { p ->
            p.asAgent("frontend").use { a ->
                assertEquals(HttpStatusCode.Forbidden, a.post("${p.baseUrl}/api/agents/frontend/stop").status, "agent token → 403")
            }
            p.client(null).use { n ->
                assertEquals(HttpStatusCode.Unauthorized, n.post("${p.baseUrl}/api/agents/frontend/stop").status, "no token → 401")
            }
        }
    }

    @Test
    fun lifecycle_unknownAgent_404() = runBlocking {
        platform().use { p ->
            p.asOperator().use { c ->
                val r = c.post("${p.baseUrl}/api/agents/ghost/stop")
                assertEquals(HttpStatusCode.NotFound, r.status)
                assertEquals("agent_not_found", r.body<ApiErrorBody>().error.code)
            }
        }
    }

    @Test
    fun statusDisplay_participantReadable_reflectsRunState() = runBlocking {
        platform().use { p ->
            p.asAgent("frontend").use { a ->
                assertEquals(HttpStatusCode.OK, a.get("${p.baseUrl}/api/agents").status, "status list is participant-readable (not operator-gated)")
            }
            p.asOperator().use { c -> c.post("${p.baseUrl}/api/agents/frontend/stop") }
            val agents = p.asOperator().use { it.get("${p.baseUrl}/api/agents").body<List<Agent>>() }
            assertEquals(AgentRunState.STOPPED, agents.first { it.id == "frontend" }.runState, "the list reflects the live run state")
        }
    }

    @Test
    fun wsLifecycle_snapshot_participant_noToken1008() = runBlocking {
        platform().use { p ->
            assertTrue("frontend" in p.lifecycleSnapshotAgentIds(p.asOperator()), "the snapshot includes the agent")
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, p.wsLifecycleCloseCode(p.client(null)), "no token → 1008")
        }
    }

    // ---- helpers ----

    private suspend fun E2ePlatform.lifecycleSnapshotAgentIds(client: HttpClient): Set<String> {
        val ids = mutableSetOf<String>()
        client.use { c ->
            c.webSocket("$wsBaseUrl/ws/lifecycle") {
                runCatching {
                    withTimeout(1500) {
                        while (true) {
                            val ev = CommJson.decodeFromString<AgentRunStateEvent>((incoming.receive() as Frame.Text).readText())
                            ids.add(ev.agentId)
                        }
                    }
                }
            }
        }
        return ids
    }

    private suspend fun E2ePlatform.wsLifecycleCloseCode(client: HttpClient): Short {
        var code: Short = -1
        client.use { c ->
            c.webSocket("$wsBaseUrl/ws/lifecycle") { code = closeReason.await()?.code ?: -1 }
        }
        return code
    }
}
