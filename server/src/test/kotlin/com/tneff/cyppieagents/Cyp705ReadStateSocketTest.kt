package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.ChannelsEvent
import com.tneff.cyppieagents.model.CommWsServerEvent
import com.tneff.cyppieagents.model.MarkReadRequest
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.ReadStateEvent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.SendMessageRequest
import com.tneff.cyppieagents.routing.CommConfig
import com.tneff.cyppieagents.routing.installComm
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CYP-705 F1 (Assist2 reviewer PIN) — the SELF-ONLY guarantee at the WIRE boundary that
 * [Cyp705ReadStateTest.readStateEventIsSelfOnly] does NOT reach. That test collects `hub.readStateEvents`
 * (the Hub-internal, PRE-socket flow) and only proves the Hub TAGS its single emit with the marker's subject
 * — trivially self-consistent. The real enforcement is the `commSocket` predicate
 * `targeted.subject == participant`, which filters the flow from `emitReadStateFor` to ONE connection. A
 * regression there — `== participant` → `state.acl.canRead(channelId, participant)` (a canRead fan-out) —
 * ships GREEN against the Hub-flow test. THIS test reds on it (the acceptance PO1 runs against the mutant).
 *
 * Two connections on `po-backend`: **A = operator** (the read-state viewer; also valid under the final
 * operator-tier policy) holds a cursor; **B = backend** does not. A new message fans a per-viewer
 * [ReadStateEvent] to the operator ONLY (backend has no cursor ⇒ UNKNOWN stays UNKNOWN, skipped). Self-only:
 * A's connection receives it; B's must NOT. Non-vacuous by construction — A's receipt proves the delta was in
 * flight, so B's silence is a real drop, not "nothing happened". Under the fan-out mutation, B (a `canRead`
 * member) also receives the operator's delta ⇒ B's assertion reds.
 */
class Cyp705ReadStateSocketTest {
    private fun config() = CommConfig(
        agents = listOf(
            Agent("po", "PO", Role.PO, "po"),
            Agent("frontend", "FE", Role.WORKER, "frontend"),
            Agent("backend", "BE", Role.WORKER, "backend"),
        ),
        tokens = mapOf("tok-po" to "po", "tok-frontend" to "frontend", "tok-backend" to "backend"),
        operatorToken = "tok-op",
        store = InMemoryMessageStore(),
    )

    private fun ApplicationTestBuilder.jsonClient(): HttpClient =
        createClient { install(ClientContentNegotiation) { json(CommJson) } }

    private fun ApplicationTestBuilder.wsClient(): HttpClient =
        createClient { install(ClientWebSockets) }

    private suspend fun HttpClient.postMessage(channel: String, token: String, body: String): Message =
        post("/api/channels/$channel/messages") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(SendMessageRequest(body))
        }.body()

    private suspend fun HttpClient.markRead(channel: String, token: String, upToSeq: Long) {
        post("/api/channels/$channel/read") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(MarkReadRequest(upToSeq))
        }
    }

    /** Drain the initial ChannelsEvent snapshot the socket emits on connect. */
    private suspend fun WebSocketSession.drainSnapshot() =
        assertIs<ChannelsEvent>(CommJson.decodeFromString(CommWsServerEvent.serializer(), (incoming.receive() as Frame.Text).readText()))

    /** Collect every [ReadStateEvent] for [channelId] arriving within [windowMs]. */
    private suspend fun WebSocketSession.readStatesWithin(channelId: String, windowMs: Long): List<ReadStateEvent> {
        val out = mutableListOf<ReadStateEvent>()
        withTimeoutOrNull(windowMs) {
            while (true) {
                val ev = CommJson.decodeFromString(CommWsServerEvent.serializer(), (incoming.receive() as Frame.Text).readText())
                if (ev is ReadStateEvent && ev.channelId == channelId) out.add(ev)
            }
        }
        return out
    }

    @Test
    fun readStateEventIsSelfOnlyOnTheWire_notCanReadFanout() = testApplication {
        application { installComm(config()) }
        val rest = jsonClient()
        val ws = wsClient()

        // Give the OPERATOR a cursor on po-backend (so a new message fans it a delta). backend has none.
        val m1 = rest.postMessage("po-backend", "tok-po", "one")
        rest.markRead("po-backend", "tok-op", m1.seq)

        // A = operator connection; B = backend connection (nested so both are live before the trigger).
        ws.webSocket("/ws/comm?token=tok-op") {
            drainSnapshot()
            ws.webSocket("/ws/comm?token=tok-backend") {
                drainSnapshot()
                delay(200) // both read-state pumps subscribed (SharedFlow has no replay)

                rest.postMessage("po-backend", "tok-po", "two") // fans a ReadStateEvent to the operator ONLY

                // B (backend) must receive NO operator read-state. Under `:290`→canRead fan-out it receives the
                // operator's delta (backend canRead po-backend) ⇒ this reds.
                val leaked = readStatesWithin("po-backend", 900)
                assertTrue(
                    leaked.isEmpty(),
                    "backend must NOT receive the operator's read-state delta — self-only (leaked=$leaked)",
                )
            }
            // A (operator) DID receive its own delta ⇒ the delta was genuinely in flight ⇒ B's silence is a real
            // drop, not a vacuous "nothing emitted".
            val own = readStatesWithin("po-backend", 900)
            assertTrue(own.isNotEmpty(), "the operator receives its OWN read-state delta (proves non-vacuity)")
        }
    }
}
