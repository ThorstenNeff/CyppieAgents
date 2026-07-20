package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.AclMatrix
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.EventPushed
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.EventsWsServerEvent
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.eventSocket
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-719 F-A (WS surface) — `/ws/events` live-tail applies the SAME `aclVisibleEvent` predicate at the pump
 * emit. A MEMBER tail receives comm.* pushes ONLY for channels it may `canRead`; the operator bypasses. Removing
 * the predicate from the emit (unwiring this surface) reddens `memberTail_*` — a denied-channel comm push would
 * reach a non-reader.
 *
 * Determinism: the pump collects the sink stream in seq order, so an earlier-appended DENIED event, if it were
 * visible, would arrive BEFORE a later-appended ALLOWED marker. Reading until the allowed marker arrives and
 * asserting the denied channel was never seen is therefore a reliable negative.
 */
class Cyp719WsAclFilterTest {

    private val registry = TokenRegistry(mapOf("tok-be" to "backend"), operatorToken = "tok-op")
    private val active = "alpha"

    // backend may read chY, NOT chX.
    private val acl = AclMatrix(
        channels = listOf(
            Channel("chX", "X", ChannelKind.GROUP, listOf("backend"), projectId = active),
            Channel("chY", "Y", ChannelKind.GROUP, listOf("backend"), projectId = active),
        ),
        entries = listOf(
            AclEntry("chX", "backend", canRead = false, canWrite = false, projectId = active),
            AclEntry("chY", "backend", canRead = true, canWrite = false, projectId = active),
        ),
        activeProjectId = active,
    )

    private fun ApplicationTestBuilder.serve(sink: InMemoryEventSink) {
        install(WebSockets)
        routing { eventSocket(sink, registry, activeProjectId = { active }, acl = { acl }) }
    }

    private fun ApplicationTestBuilder.wsClient() = createClient { install(ClientWebSockets) }

    private fun comm(channel: String) =
        draft(agent = "backend", team = active, type = EventType.COMM_SENT, detail = buildJsonObject { put("channel", channel) })

    private suspend fun io.ktor.websocket.WebSocketSession.pushedChannelOrNull(): String? {
        val f = withTimeoutOrNull(50) { incoming.receive() } as? Frame.Text ?: return null
        val pushed = CommJson.decodeFromString<EventsWsServerEvent>(f.readText()) as? EventPushed ?: return null
        return (pushed.event.detail["channel"] as? JsonPrimitive)?.contentOrNull ?: "<none>"
    }

    @Test fun memberTail_receivesReadableComm_neverUnreadable() = testApplication {
        val sink = InMemoryEventSink(SystemTimeSource())
        serve(sink)
        wsClient().webSocket("/ws/events?token=tok-be") {
            val seen = mutableListOf<String?>()
            val gotAllowed = withTimeout(5_000) {
                var hit = false
                while (!hit) {
                    sink.append(comm("chX")) // denied — must NEVER reach backend
                    sink.append(comm("chY")) // allowed — the marker that proves the denied one (appended first) was dropped
                    pushedChannelOrNull()?.let { ch ->
                        seen += ch
                        if (ch == "chY") hit = true
                    }
                }
                hit
            }
            assertTrue(gotAllowed, "the readable-channel (chY) comm push must arrive")
            assertTrue(seen.none { it == "chX" }, "an unreadable-channel (chX) comm push must NEVER reach a non-reader: $seen")
            close()
        }
    }

    @Test fun operatorTail_bypassesFilter_receivesUnreadableComm() = testApplication {
        val sink = InMemoryEventSink(SystemTimeSource())
        serve(sink)
        wsClient().webSocket("/ws/events?token=tok-op") {
            val gotDenied = withTimeout(5_000) {
                var hit = false
                while (!hit) {
                    sink.append(comm("chX")) // only-in-chX; a non-operator would never see it, the operator does
                    if (pushedChannelOrNull() == "chX") hit = true
                }
                hit
            }
            assertTrue(gotDenied, "operator bypasses the comm ACL filter → receives even a chX push (anti-vacuity)")
            close()
        }
    }
}
