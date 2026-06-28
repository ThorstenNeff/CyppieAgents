package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.AgentRunStateEvent
import com.tneff.cyppieagents.model.ApiError
import com.tneff.cyppieagents.model.ApiErrorBody
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CYP-73: the real lifecycle clients against an embedded Ktor server, on the frozen contract.
 *  - `/ws/lifecycle` streams content-free [AgentRunStateEvent] → mapped to [AgentLifecycleEvent].
 *  - a non-2xx control decodes the [ApiError] `code` into [AgentLifecycleHttpException] (honest 409).
 */
class AgentLifecycleClientE2eTest {

    @Test
    fun lifecycleSocket_streamsRunStateEvents_mappedToClientState() = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            install(ServerWebSockets)
            routing {
                webSocket("/ws/lifecycle") {
                    send(Frame.Text(CommJson.encodeToString(AgentRunStateEvent.serializer(), AgentRunStateEvent("backend", AgentRunState.STOPPED))))
                    send(Frame.Text(CommJson.encodeToString(AgentRunStateEvent.serializer(), AgentRunStateEvent("backend", AgentRunState.RUNNING))))
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO) { install(ClientWebSockets) }
            try {
                val source = AgentLifecycleLiveSource(client, "http://127.0.0.1:$port", "ws://127.0.0.1:$port", token = "op")
                val events = withTimeout(10_000) { source.events().toList2(2) }
                assertEquals(AgentLifecycleEvent("backend", AgentLifecycleState.STOPPED), events[0])
                assertEquals(AgentLifecycleEvent("backend", AgentLifecycleState.RUNNING), events[1])
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }

    @Test
    fun control_conflict_decodesApiErrorCode() = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            routing {
                post("/api/agents/{id}/start") {
                    val body = CommJson.encodeToString(ApiErrorBody.serializer(), ApiErrorBody(ApiError("already_running", "agent 'backend' is already running")))
                    call.respondText(body, status = HttpStatusCode.Conflict)
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO)
            try {
                val repo = AgentLifecycleRepository(client, "http://127.0.0.1:$port", operatorToken = "op")
                try {
                    repo.start("backend")
                    fail("expected AgentLifecycleHttpException")
                } catch (e: AgentLifecycleHttpException) {
                    assertEquals(409, e.status)
                    assertEquals("already_running", e.code)
                }
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }

    @Test
    fun snapshot_unreachableServer_failsClosedToEmpty() = runBlocking {
        // No server on this port → GET throws ConnectException. snapshot() must FAIL-CLOSED (return
        // emptyMap, never throw) so the header stays UNKNOWN instead of crashing the window. Mutation:
        // `catch (e: Throwable) -> throw e` makes this the only test that goes RED.
        val client = HttpClient(CIO)
        try {
            val source = AgentLifecycleLiveSource(client, "http://127.0.0.1:1", "ws://127.0.0.1:1", token = "op")
            val snap = withTimeout(10_000) { source.snapshot() }
            assertEquals(emptyMap(), snap, "unreachable server must fail-closed to an empty snapshot, not throw")
        } finally {
            client.close()
        }
    }

    @Test
    fun runStateMapping_coversAllServerValues() {
        assertEquals(AgentLifecycleState.RUNNING, AgentRunState.RUNNING.toLifecycleState())
        assertEquals(AgentLifecycleState.STOPPED, AgentRunState.STOPPED.toLifecycleState())
        assertEquals(AgentLifecycleState.ERROR, AgentRunState.ERROR.toLifecycleState())
    }
}

/** Collect exactly [n] items from a (possibly endless/reconnecting) flow. */
private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.toList2(n: Int): List<T> {
    val out = ArrayList<T>(n)
    try {
        collect {
            out.add(it)
            if (out.size >= n) throw kotlinx.coroutines.CancellationException("done")
        }
    } catch (_: kotlinx.coroutines.CancellationException) {
    }
    return out
}
