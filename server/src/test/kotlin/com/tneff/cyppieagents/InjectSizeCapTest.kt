package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.SendMessageRequest
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import com.tneff.cyppieagents.routing.CommConfig
import com.tneff.cyppieagents.routing.MessageInput
import com.tneff.cyppieagents.agentevents.InMemoryAgentEventStore
import com.tneff.cyppieagents.routing.installAgentSocket
import com.tneff.cyppieagents.routing.installComm
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-143 — the send/inject boundary caps oversized/blank input at BOTH the REST send route
 * ([installComm] `POST /api/channels/{id}/messages`) and the WS inject loop ([installAgentSocket]
 * `/ws/agent`), single-sourced through [MessageInput].
 *
 * **Mutation (the ticket's teeth):** delete the `MessageInput.requireValidBody(...)` call at either
 * site → the oversized/blank case is accepted (201 / injected) → the matching test below reddens,
 * the others stay green.
 */
class InjectSizeCapTest {

    private fun config() = CommConfig(
        agents = listOf(
            Agent("po", "PO", Role.PO, "po"),
            Agent("backend", "BE", Role.WORKER, "backend"),
        ),
        tokens = mapOf("tok-po" to "po", "tok-backend" to "backend"),
        operatorToken = "tok-op",
        store = InMemoryMessageStore(),
    )

    /** One character over the cap — the smallest input that must be rejected. */
    private val oversized = "x".repeat(MessageInput.MAX_BODY_CHARS + 1)

    // ---- REST send path (CommRoutes POST) ----

    @Test
    fun restRejectsOversizedBody() = testApplication {
        application { installComm(config()) }
        val client = createClient { install(ClientContentNegotiation) { json(CommJson) } }
        val res = client.post("/api/channels/po-backend/messages") {
            bearerAuth("tok-backend"); contentType(ContentType.Application.Json)
            setBody(SendMessageRequest(oversized))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, res.status)
    }

    @Test
    fun restRejectsBlankBody() = testApplication {
        application { installComm(config()) }
        val client = createClient { install(ClientContentNegotiation) { json(CommJson) } }
        val res = client.post("/api/channels/po-backend/messages") {
            bearerAuth("tok-backend"); contentType(ContentType.Application.Json)
            setBody(SendMessageRequest("   "))
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun restAcceptsNormalBody() = testApplication {
        application { installComm(config()) }
        val client = createClient { install(ClientContentNegotiation) { json(CommJson) } }
        val res = client.post("/api/channels/po-backend/messages") {
            bearerAuth("tok-backend"); contentType(ContentType.Application.Json)
            setBody(SendMessageRequest("ready"))
        }
        assertEquals(HttpStatusCode.Created, res.status)
    }

    // ---- WS inject path (/ws/agent) ----

    /** Records injected turns so the test can assert an oversized inject never reaches the session. */
    private class RecordingSession(override val agentId: String) : ConnectorSession {
        private val _events = MutableSharedFlow<StreamJsonEvent>(replay = 8, extraBufferCapacity = 16)
        override val events: Flow<StreamJsonEvent> = _events
        val received = CopyOnWriteArrayList<UserTurn>()
        override suspend fun sendTurn(turn: UserTurn) { received.add(turn) }
        override fun close() {}
    }

    @Test
    fun wsRejectsOversizedInjectAndDoesNotInject() = testApplication {
        val sessions = ConnectorSessions()
        val fake = RecordingSession("backend")
        sessions.register(fake)
        application { installAgentSocket(sessions, authorize = { true }, agentEvents = InMemoryAgentEventStore()) }
        val client = createClient { install(ClientWebSockets) }

        client.webSocket("/ws/agent?agentId=backend") {
            send(Frame.Text(CommJson.encodeToString(UserTurn.serializer(), UserTurn(oversized))))
            // The server rejects fail-closed by closing the socket — it must not inject.
            assertEquals(CloseReason.Codes.CANNOT_ACCEPT.code, closeReason.await()?.code)
        }
        assertTrue(fake.received.isEmpty(), "oversized turn must not be injected")
    }
}
