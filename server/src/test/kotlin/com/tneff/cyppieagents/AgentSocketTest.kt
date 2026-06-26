package com.tneff.cyppieagents

import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.model.AgentMessage
import com.tneff.cyppieagents.model.AssistantEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.SystemEvent
import com.tneff.cyppieagents.model.TextBlock
import com.tneff.cyppieagents.model.UserTurn
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.installAgentSocket
import com.tneff.cyppieagents.routing.tokenAuthorize
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies the /ws/agent frame contract end-to-end over a real WebSocket against a fake session
 * (no live `claude` process). This is the endpoint Dev wires his MappingAgentSession against.
 */
class AgentSocketTest {

    /** In-memory session: replay buffer makes event delivery deterministic regardless of timing. */
    private class FakeConnectorSession(override val agentId: String) : ConnectorSession {
        private val _events = MutableSharedFlow<StreamJsonEvent>(replay = 16, extraBufferCapacity = 64)
        override val events: Flow<StreamJsonEvent> = _events
        val receivedTurns = CopyOnWriteArrayList<UserTurn>()

        suspend fun emit(event: StreamJsonEvent) = _events.emit(event)

        override suspend fun sendTurn(turn: UserTurn) {
            receivedTurns.add(turn)
            // Echo an assistant ack so a client can observe the round-trip deterministically.
            _events.emit(AssistantEvent(message = AgentMessage(content = listOf(TextBlock("ack:${turn.text}"))), sessionId = "fake"))
        }

        override fun close() {}
    }

    @Test
    fun serverStreamsEventsAsStreamJsonFrames() = testApplication {
        val sessions = ConnectorSessions()
        val fake = FakeConnectorSession("backend")
        sessions.register(fake)
        application { installAgentSocket(sessions, authorize = { true }) }
        val client = createClient { install(ClientWebSockets) }

        // Buffered (replay) before the client connects → delivered on subscribe.
        fake.emit(SystemEvent(subtype = "init", sessionId = "s1"))
        fake.emit(AssistantEvent(message = AgentMessage(content = listOf(TextBlock("hello"))), sessionId = "s1"))

        client.webSocket("/ws/agent?agentId=backend") {
            val first = CommJson.decodeFromString<StreamJsonEvent>((incoming.receive() as Frame.Text).readText())
            assertIs<SystemEvent>(first)
            val second = CommJson.decodeFromString<StreamJsonEvent>((incoming.receive() as Frame.Text).readText())
            val assistant = assertIs<AssistantEvent>(second)
            assertEquals("hello", assertIs<TextBlock>(assistant.message.content.single()).text)
        }
    }

    @Test
    fun clientUserTurnIsInjectedIntoSession() = testApplication {
        val sessions = ConnectorSessions()
        val fake = FakeConnectorSession("backend")
        sessions.register(fake)
        application { installAgentSocket(sessions, authorize = { true }) }
        val client = createClient { install(ClientWebSockets) }

        client.webSocket("/ws/agent?agentId=backend") {
            // Client → Server: a UserTurn frame ({"text":"…"}).
            send(Frame.Text(CommJson.encodeToString(UserTurn.serializer(), UserTurn("do the thing"))))
            // Read the ack echo to synchronize on the server having processed it.
            val ack = assertIs<AssistantEvent>(
                CommJson.decodeFromString<StreamJsonEvent>((incoming.receive() as Frame.Text).readText()),
            )
            assertTrue(assertIs<TextBlock>(ack.message.content.single()).text.contains("do the thing"))
        }
        assertEquals(listOf("do the thing"), fake.receivedTurns.map { it.text })
    }

    @Test
    fun defaultAuthIsFailClosed() = testApplication {
        // No authorize predicate → deny everything (F-B): an open socket could otherwise drive an agent.
        val sessions = ConnectorSessions()
        sessions.register(FakeConnectorSession("backend"))
        application { installAgentSocket(sessions) }
        val client = createClient { install(ClientWebSockets) }
        client.webSocket("/ws/agent?agentId=backend") {
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
        }
    }

    @Test
    fun tokenAuthorizeAllowsOperatorAndRejectsNoToken() = testApplication {
        val sessions = ConnectorSessions()
        sessions.register(FakeConnectorSession("backend"))
        val registry = TokenRegistry(mapOf("tok-backend" to "backend"), operatorToken = "tok-op")
        application { installAgentSocket(sessions, authorize = tokenAuthorize(registry)) }
        val client = createClient { install(ClientWebSockets) }

        // No token → fail-closed.
        client.webSocket("/ws/agent?agentId=backend") {
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
        }
        // Operator token via ?token= → allowed (connection stays open; we can send a turn).
        client.webSocket("/ws/agent?agentId=backend&token=tok-op") {
            send(Frame.Text(CommJson.encodeToString(UserTurn.serializer(), UserTurn("hi"))))
            val ack = assertIs<AssistantEvent>(
                CommJson.decodeFromString<StreamJsonEvent>((incoming.receive() as Frame.Text).readText()),
            )
            assertTrue(assertIs<TextBlock>(ack.message.content.single()).text.contains("hi"))
        }
    }

    @Test
    fun tokenAuthorizeDeniesAgentTokenForForeignAgent() = testApplication {
        // CYP-25 (security branch): an agent token may watch ONLY its own session. frontend's token
        // requesting backend's stream must be denied — otherwise one agent could drive another.
        val sessions = ConnectorSessions()
        sessions.register(FakeConnectorSession("backend"))
        val registry = TokenRegistry(
            mapOf("tok-backend" to "backend", "tok-frontend" to "frontend"),
            operatorToken = "tok-op",
        )
        application { installAgentSocket(sessions, authorize = tokenAuthorize(registry)) }
        val client = createClient { install(ClientWebSockets) }

        // Cross-agent: frontend's token on backend's socket → deny.
        client.webSocket("/ws/agent?agentId=backend&token=tok-frontend") {
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
        }
        // Own agent: backend's token on backend's socket → allowed.
        client.webSocket("/ws/agent?agentId=backend&token=tok-backend") {
            send(Frame.Text(CommJson.encodeToString(UserTurn.serializer(), UserTurn("self"))))
            val ack = assertIs<AssistantEvent>(
                CommJson.decodeFromString<StreamJsonEvent>((incoming.receive() as Frame.Text).readText()),
            )
            assertTrue(assertIs<TextBlock>(ack.message.content.single()).text.contains("self"))
        }
    }

    @Test
    fun unknownAgentIsRejected() = testApplication {
        application { installAgentSocket(ConnectorSessions(), authorize = { true }) }
        val client = createClient { install(ClientWebSockets) }
        client.webSocket("/ws/agent?agentId=ghost") {
            val reason = closeReason.await()
            assertEquals(CloseReason.Codes.CANNOT_ACCEPT.code, reason?.code)
        }
    }

    @Test
    fun missingAgentIdIsRejected() = testApplication {
        application { installAgentSocket(ConnectorSessions(), authorize = { true }) }
        val client = createClient { install(ClientWebSockets) }
        client.webSocket("/ws/agent") {
            val reason = closeReason.await()
            assertEquals(CloseReason.Codes.CANNOT_ACCEPT.code, reason?.code)
        }
    }
}
