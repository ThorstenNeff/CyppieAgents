package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.connector.Connector
import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.ProviderInfo
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * E2E-HUNT (Team-2, adversarial) — **concurrent turns to one agent through the REAL stack** (H2). Many
 * simultaneous `/ws/agent` connections each drive a distinct turn at the same agent; the invariant is
 * exactly-once delivery to its live session under concurrency: **no lost turn, no duplicate.**
 *
 * This is the dynamic complement to the static finding that the production `ClaudeCodeSession.sendTurn`
 * serializes via `SessionTurnQueue`: here the whole path is exercised — N concurrent WS → `AgentSocket`
 * per-frame session resolution → `ConnectorSessions` (ConcurrentHashMap) → `sendTurn`. A recording connector
 * (thread-safe) is injected via `connectorFactory`.
 *
 * Flake-proof by construction: every socket is **held open** (awaits a shared gate) until all N turns are
 * recorded, so a socket never closes before the server reads its frame. Therefore a timeout waiting for the
 * count means a **genuine server-side loss** (RED = a real bug to report), not a premature-close artifact; a
 * count/other-than-N or a wrong set means a duplicate/misroute.
 */
class DesktopConcurrentTurnsHuntE2eTest {

    private class RecordingConnector : Connector {
        override val capabilities = Capabilities(
            CapabilityStatus.AVAILABLE, CapabilityStatus.AVAILABLE, CapabilityStatus.AVAILABLE,
            CapabilityStatus.AVAILABLE, CapabilityStatus.AVAILABLE, ConnectorKind.STREAM_JSON,
        )
        override val provider: ProviderInfo = ProviderInfo.CLAUDE
        private val turns = ConcurrentHashMap<String, CopyOnWriteArrayList<UserTurn>>()
        fun received(agentId: String): List<UserTurn> = turns[agentId].orEmpty()
        override fun open(agentId: String): ConnectorSession = object : ConnectorSession {
            override val agentId = agentId
            override val events: Flow<StreamJsonEvent> = emptyFlow()
            override suspend fun sendTurn(turn: UserTurn) { turns.getOrPut(agentId) { CopyOnWriteArrayList() }.add(turn) }
            override fun close() {}
        }
    }

    private fun projects() =
        listOf(SeedProject("default", "Default", listOf(SeedAgent("po", Role.PO), SeedAgent("backend"))))

    @Test
    fun concurrentTurns_fromManyConnections_allDeliveredExactlyOnce() = runBlocking {
        val rec = RecordingConnector()
        e2ePlatform(projects(), connectorFactory = { rec }).use { p ->
            val n = 24
            val gate = CompletableDeferred<Unit>() // holds each socket open until every turn is recorded
            p.asOperator().use { c ->
                val senders = (0 until n).map { i ->
                    async(Dispatchers.IO) {
                        c.webSocket("${p.wsBaseUrl}/ws/agent?agentId=backend") {
                            send(Frame.Text(CommJson.encodeToString(UserTurn.serializer(), UserTurn("turn-$i"))))
                            gate.await() // keep the WS open so the server definitely reads this frame
                        }
                    }
                }
                // A timeout here = a genuine lost turn (the sockets are all still open).
                withTimeout(10_000) { while (rec.received("backend").size < n) delay(20) }
                gate.complete(Unit)
                senders.awaitAll()
            }

            val bodies = rec.received("backend").map { it.text }
            assertEquals(n, bodies.size, "exactly $n turns delivered — no loss, no duplicate under $n concurrent connections")
            assertEquals((0 until n).map { "turn-$it" }.toSet(), bodies.toSet(), "every distinct concurrent turn reached the live session")
        }
    }
}
