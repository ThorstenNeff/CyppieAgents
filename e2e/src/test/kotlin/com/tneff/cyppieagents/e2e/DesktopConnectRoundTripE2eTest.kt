package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.connector.Connector
import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.ProviderInfo
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Desktop-Hub E2E — **connect: session in roster + turn round-trip** (Team-2, Post-Login-Lane).
 *
 * The desktop "connect" step, proven through the REAL embedded platform (real `installPlatform`, real
 * `tokenAuthorize` on `/ws/agent`, real lifecycle start + roster): a started agent shows **RUNNING** in
 * `GET /api/agents`, and a human/operator turn typed over the real `/ws/agent` WS is **delivered to that
 * agent's live connector session** (D7 — the input field is "a message to the agent"). CYP-382's
 * `Cyp382WsSessionPerFrameTest` pins the per-frame inject at the isolated `installAgentSocket` level; the
 * NET-NEW value here is the SAME round-trip through the whole real stack (real auth + real start→roster +
 * real ConnectorSessions), which is what actually runs on the desktop.
 *
 * Observability: a recording connector is injected via the `connectorFactory` seam (CYP-120/124) so the turn
 * that reaches the live session is asserted directly — no real `claude`. Non-vacuity: each round-trip tooth
 * first asserts the session recorded NOTHING before the send (the send is what changes it), and the routing
 * tooth proves the turn reaches ONLY the targeted agent (absence-of-signal on the non-target).
 *
 * Keystone mutation (run, then reverted — prod pristine): drop `live.sendTurn(turn)` in `AgentSocket`'s inbound
 * loop → the round-trip tooth goes RED (the awaited delivery times out) while the roster/start tooth is
 * unaffected. Fake connector/git/spawner → CI-green now; a real-`claude` turn is the live-stack tooth.
 */
class DesktopConnectRoundTripE2eTest {

    /** A connector whose sessions RECORD every injected turn per agentId (aggregated across any re-open). */
    private class RecordingConnector : Connector {
        override val capabilities =
            Capabilities(
                CapabilityStatus.AVAILABLE, CapabilityStatus.AVAILABLE, CapabilityStatus.AVAILABLE,
                CapabilityStatus.AVAILABLE, CapabilityStatus.AVAILABLE, ConnectorKind.STREAM_JSON,
            )
        override val provider: ProviderInfo = ProviderInfo.CLAUDE

        private val turns = ConcurrentHashMap<String, CopyOnWriteArrayList<UserTurn>>()
        private val firstTurn = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        fun received(agentId: String): List<UserTurn> = turns[agentId].orEmpty()
        fun awaitFirstTurn(agentId: String): CompletableDeferred<Unit> =
            firstTurn.getOrPut(agentId) { CompletableDeferred() }

        override fun open(agentId: String): ConnectorSession = RecordingSession(agentId)

        private inner class RecordingSession(override val agentId: String) : ConnectorSession {
            override val events: Flow<StreamJsonEvent> = emptyFlow()
            override suspend fun sendTurn(turn: UserTurn) {
                turns.getOrPut(agentId) { CopyOnWriteArrayList() }.add(turn)
                awaitFirstTurn(agentId).complete(Unit)
            }
            override fun close() {}
        }
    }

    private suspend fun E2ePlatform.roster(): List<Agent> =
        asOperator().use { it.get("$baseUrl/api/agents").body() }

    private suspend fun E2ePlatform.start(agentId: String): HttpStatusCode =
        asOperator().use { it.post("$baseUrl/api/agents/$agentId/start").status }

    /** Drive a human/operator turn over the REAL `/ws/agent` WS (operator token authorizes any active agent). */
    private suspend fun E2ePlatform.driveTurn(agentId: String, text: String) {
        asOperator().use { c ->
            c.webSocket("$wsBaseUrl/ws/agent?agentId=$agentId") {
                send(Frame.Text(CommJson.encodeToString(UserTurn.serializer(), UserTurn(text))))
            }
        }
    }

    @Test
    fun startedAgent_isRunningInRoster_andReceivesATurnOverRealWsAgent() = runBlocking {
        val rec = RecordingConnector()
        e2ePlatform(
            listOf(SeedProject("default", "Default", listOf(SeedAgent("po", Role.PO), SeedAgent("backend")))),
            connectorFactory = { rec },
        ).use { p ->
            // Roster / connect state: the booted agent is RUNNING and its session is open (via the connector) — the
            // connect state the desktop renders. (Config agents boot RUNNING; a re-`start` returns 409, confirming
            // the live session already exists — so this proves the connected state without re-driving lifecycle.)
            assertTrue(p.roster().any { it.id == "backend" }, "backend is in the roster")
            assertEquals(
                AgentRunState.RUNNING, p.roster().first { it.id == "backend" }.runState,
                "the connected agent shows RUNNING in the roster",
            )
            assertEquals(HttpStatusCode.Conflict, p.start("backend"), "already connected — its live session exists")

            // Precondition (non-vacuity): nothing has been injected yet.
            assertTrue(rec.received("backend").isEmpty(), "no turn recorded before the operator drives one")

            // Round-trip: an operator turn over the real /ws/agent reaches backend's live session.
            p.driveTurn("backend", "investigate the CYP-560 flake")
            withTimeout(3_000) { rec.awaitFirstTurn("backend").await() }
            assertEquals(
                listOf("investigate the CYP-560 flake"), rec.received("backend").map { it.text },
                "the turn typed over /ws/agent is delivered to the started agent's connector session",
            )
        }
    }

    @Test
    fun aTurn_reachesOnlyTheTargetedAgent_notAnotherLiveOne() = runBlocking {
        val rec = RecordingConnector()
        e2ePlatform(
            listOf(
                SeedProject(
                    "default", "Default",
                    listOf(SeedAgent("po", Role.PO), SeedAgent("backend"), SeedAgent("frontend")),
                ),
            ),
            connectorFactory = { rec },
        ).use { p ->
            // Both boot RUNNING with live sessions (connected); drive backend only.
            assertEquals(AgentRunState.RUNNING, p.roster().first { it.id == "frontend" }.runState, "frontend is connected too")
            p.driveTurn("backend", "backend-only task")
            withTimeout(3_000) { rec.awaitFirstTurn("backend").await() }

            assertEquals(listOf("backend-only task"), rec.received("backend").map { it.text }, "backend got its turn")
            // Absence-of-signal: the OTHER live agent's session received nothing — the turn is routed, not broadcast.
            assertTrue(rec.received("frontend").isEmpty(), "a turn to backend never reaches frontend's live session")
        }
    }
}
