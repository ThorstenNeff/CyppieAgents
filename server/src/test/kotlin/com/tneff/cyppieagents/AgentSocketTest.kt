package com.tneff.cyppieagents

import com.tneff.cyppieagents.agentevents.InMemoryAgentEventStore
import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.model.AgentMessage
import com.tneff.cyppieagents.model.AssistantEvent
import com.tneff.cyppieagents.model.StoredAgentEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.SystemEvent
import com.tneff.cyppieagents.model.TextBlock
import com.tneff.cyppieagents.model.UserTurn
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.installAgentSocket
import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.routing.tokenAuthorize
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies the /ws/agent frame contract end-to-end over a real WebSocket. Since CYP-384 the transcript store
 * ([InMemoryAgentEventStore]) is the SINGLE output source (the live-connector fallback that held a stale
 * session ref across restarts is gone), so output tests feed the store and read [StoredAgentEvent] frames;
 * inject tests assert on the session's recorded turns (no captured session ref exists any more).
 */
class AgentSocketTest {

    /** In-memory session that records injected turns (output now flows through the durable store, not here). */
    private class FakeConnectorSession(override val agentId: String) : ConnectorSession {
        private val _events = MutableSharedFlow<StreamJsonEvent>(replay = 16, extraBufferCapacity = 64)
        override val events: Flow<StreamJsonEvent> = _events
        val receivedTurns = CopyOnWriteArrayList<UserTurn>()
        override suspend fun sendTurn(turn: UserTurn) { receivedTurns.add(turn) }
        override fun close() {}
    }

    private suspend fun awaitTurn(fake: FakeConnectorSession, text: String) =
        withTimeout(3_000) { while (fake.receivedTurns.none { it.text == text }) delay(10) }

    @Test
    fun serverStreamsDurableTranscriptFrames() = testApplication {
        val store = InMemoryAgentEventStore()
        store.append("backend", "default", 1L, SystemEvent(subtype = "init", sessionId = "s1"))
        store.append("backend", "default", 2L, AssistantEvent(message = AgentMessage(content = listOf(TextBlock("hello"))), sessionId = "s1"))
        application { installAgentSocket(ConnectorSessions(), authorize = { true }, agentEvents = store) }
        val client = createClient { install(ClientWebSockets) }

        client.webSocket("/ws/agent?agentId=backend") {
            val first = CommJson.decodeFromString<StoredAgentEvent>((incoming.receive() as Frame.Text).readText())
            assertIs<SystemEvent>(first.event)
            val second = CommJson.decodeFromString<StoredAgentEvent>((incoming.receive() as Frame.Text).readText())
            val assistant = assertIs<AssistantEvent>(second.event)
            assertEquals("hello", assertIs<TextBlock>(assistant.message.content.single()).text)
        }
    }

    @Test
    fun clientUserTurnIsInjectedIntoSession() = testApplication {
        val sessions = ConnectorSessions()
        val fake = FakeConnectorSession("backend")
        sessions.register(fake)
        application { installAgentSocket(sessions, authorize = { true }, agentEvents = InMemoryAgentEventStore()) }
        val client = createClient { install(ClientWebSockets) }

        client.webSocket("/ws/agent?agentId=backend") {
            send(Frame.Text(CommJson.encodeToString(UserTurn.serializer(), UserTurn("do the thing"))))
            awaitTurn(fake, "do the thing")
        }
        assertEquals(listOf("do the thing"), fake.receivedTurns.map { it.text })
    }

    @Test
    fun defaultAuthIsFailClosed() = testApplication {
        // No authorize predicate → deny everything (F-B): an open socket could otherwise drive an agent.
        val sessions = ConnectorSessions()
        sessions.register(FakeConnectorSession("backend"))
        application { installAgentSocket(sessions, agentEvents = InMemoryAgentEventStore()) }
        val client = createClient { install(ClientWebSockets) }
        client.webSocket("/ws/agent?agentId=backend") {
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
        }
    }

    @Test
    fun tokenAuthorizeAllowsOperatorAndRejectsNoToken() = testApplication {
        val sessions = ConnectorSessions()
        val fake = FakeConnectorSession("backend")
        sessions.register(fake)
        val registry = TokenRegistry(mapOf("tok-backend" to "backend"), operatorToken = "tok-op", loopbackPosture = true)
        application { installAgentSocket(sessions, authorize = tokenAuthorize(registry, AuthDeps(registry)), agentEvents = InMemoryAgentEventStore()) }
        val client = createClient { install(ClientWebSockets) }

        // No token → fail-closed.
        client.webSocket("/ws/agent?agentId=backend") {
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
        }
        // Operator token via ?token= → allowed (the turn reaches the session).
        client.webSocket("/ws/agent?agentId=backend&token=tok-op") {
            send(Frame.Text(CommJson.encodeToString(UserTurn.serializer(), UserTurn("hi"))))
            awaitTurn(fake, "hi")
        }
    }

    @Test
    fun tokenAuthorizeDeniesAgentTokenForForeignAgent() = testApplication {
        // CYP-25 (security branch): an agent token may drive ONLY its own session.
        val sessions = ConnectorSessions()
        val fake = FakeConnectorSession("backend")
        sessions.register(fake)
        val registry = TokenRegistry(
            mapOf("tok-backend" to "backend", "tok-frontend" to "frontend"),
            operatorToken = "tok-op",
        loopbackPosture = true)
        application { installAgentSocket(sessions, authorize = tokenAuthorize(registry, AuthDeps(registry)), agentEvents = InMemoryAgentEventStore()) }
        val client = createClient { install(ClientWebSockets) }

        // Cross-agent: frontend's token on backend's socket → deny.
        client.webSocket("/ws/agent?agentId=backend&token=tok-frontend") {
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
        }
        // Own agent: backend's token on backend's socket → allowed.
        client.webSocket("/ws/agent?agentId=backend&token=tok-backend") {
            send(Frame.Text(CommJson.encodeToString(UserTurn.serializer(), UserTurn("self"))))
            awaitTurn(fake, "self")
        }
    }

    @Test
    fun agentNotInActiveProjectSliceIsRejected_evenWithLiveSession() = testApplication {
        // CYP-255 ② — a LIVE session exists for "backend", but "backend" is NOT in the active project's
        // slice → rejected fail-closed (a same-id agent in another project must not attach cross-project).
        val sessions = ConnectorSessions()
        sessions.register(FakeConnectorSession("backend"))
        application { installAgentSocket(sessions, authorize = { true }, agentEvents = InMemoryAgentEventStore(), activeAgentIds = { setOf("frontend") }) }
        val client = createClient { install(ClientWebSockets) }
        client.webSocket("/ws/agent?agentId=backend") {
            assertEquals(CloseReason.Codes.CANNOT_ACCEPT.code, closeReason.await()?.code)
        }
    }

    @Test
    fun agentInActiveProjectSliceWithSessionIsAdmitted() = testApplication {
        // Non-vacuous partner to the reject above: same live session, but the active slice INCLUDES backend.
        val sessions = ConnectorSessions()
        val fake = FakeConnectorSession("backend")
        sessions.register(fake)
        application { installAgentSocket(sessions, authorize = { true }, agentEvents = InMemoryAgentEventStore(), activeAgentIds = { setOf("backend") }) }
        val client = createClient { install(ClientWebSockets) }
        client.webSocket("/ws/agent?agentId=backend") {
            send(Frame.Text(CommJson.encodeToString(UserTurn.serializer(), UserTurn("hi"))))
            awaitTurn(fake, "hi")
        }
    }

    @Test
    fun unknownAgentIsRejected() = testApplication {
        // CYP-384: the connect-time "no live session" gate is gone (output is the agentId-keyed durable stream).
        // The unknown-agent rejection now runs through the SAME guard production uses — `activeAgentIds`: an
        // agentId not in the active project's slice is rejected fail-closed. (Same live behavior, real mechanism.)
        application {
            installAgentSocket(ConnectorSessions(), authorize = { true }, agentEvents = InMemoryAgentEventStore(), activeAgentIds = { setOf("backend") })
        }
        val client = createClient { install(ClientWebSockets) }
        client.webSocket("/ws/agent?agentId=ghost") {
            assertEquals(CloseReason.Codes.CANNOT_ACCEPT.code, closeReason.await()?.code)
        }
    }

    @Test
    fun missingAgentIdIsRejected() = testApplication {
        application { installAgentSocket(ConnectorSessions(), authorize = { true }, agentEvents = InMemoryAgentEventStore()) }
        val client = createClient { install(ClientWebSockets) }
        client.webSocket("/ws/agent") {
            assertEquals(CloseReason.Codes.CANNOT_ACCEPT.code, closeReason.await()?.code)
        }
    }
}
