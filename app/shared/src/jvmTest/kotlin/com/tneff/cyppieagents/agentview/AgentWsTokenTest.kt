package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.net.Backoff
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-230 — the agent-WS credential shape, end-to-end against an embedded `/ws/agent` that captures what the client
 * actually sends. Security: the deployed SPA must send NO guessable per-agent token — a BLANK token → NO `?token=`
 * param and NO `Authorization` leave the client (the server authenticates via the same-origin session cookie the
 * browser attaches to the WS handshake). A REAL token (native / agent-self / break-glass operator) is still sent.
 */
class AgentWsTokenTest {

    private val frame = """{"seq":1,"agentId":"backend","projectId":"p","tsMs":0,"event":{"type":"result","subtype":"success","is_error":false,"uuid":"u-1"}}"""

    private fun capture(token: String): Pair<String?, String?> = runBlocking {
        val tokens = Collections.synchronizedList(mutableListOf<String?>())
        val auths = Collections.synchronizedList(mutableListOf<String?>())
        val server = embeddedServer(Netty, port = 0) {
            install(ServerWebSockets)
            routing {
                webSocket("/ws/agent") {
                    tokens.add(call.request.queryParameters["token"])
                    auths.add(call.request.headers[HttpHeaders.Authorization])
                    send(Frame.Text(frame))
                    for (f in incoming) { /* keep open — no drop, no reconnect */ }
                }
            }
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO) { install(ClientWebSockets) }
            try {
                val ws = AgentWsClient(client, "ws://127.0.0.1:$port", "backend", token, backoff = Backoff(initialMs = 10L, maxMs = 20L))
                withTimeout(15_000) { ws.events.take(1).toList() }
                tokens.first() to auths.first()
            } finally {
                client.close()
            }
        } finally {
            server.stop(0, 100)
        }
    }

    @Test
    fun blankToken_sendsNoTokenParam_andNoBearer() {
        val (token, auth) = capture("")
        assertNull(token, "a blank token must NOT leave the client as `?token=` (no guessable/empty token; cookie auths)")
        assertNull(auth, "a blank token must NOT produce an (empty) Authorization header")
    }

    @Test
    fun realToken_isStillSent_asParamAndBearer() {
        val (token, auth) = capture("tok-xyz")
        assertEquals("tok-xyz", token, "a real token (native/agent-self/operator) still rides as `?token=`")
        assertEquals("Bearer tok-xyz", auth)
    }
}
