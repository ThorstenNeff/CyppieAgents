package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.agentmgmt.AgentManagementHttpRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * CYP-903 — the operator-credential emitters attach `Bearer`/`?token=` **only when a token is present**, mirroring
 * `AgentWsClient`/CYP-230. The deployed web SPA is token-less, so it sends NO credential header/param and the
 * same-origin session cookie authenticates (the server prefers the cookie over an empty `Bearer `). Consistency /
 * defense-in-depth (server already prefers the cookie — routing/Auth.kt `ifBlank{null}` — so this is not load-bearing),
 * but it stops a blank/guessable credential ever leaving the client and keeps the whole client on one rule.
 *
 * One axis per emitter; each MUT (drop the `isNotBlank` guard → unconditional emit) reddens its token-less assertion.
 */
class Cyp903EmitterCookieGuardTest {

    // --- StatusMuxClient: the WS URL omits `?token=` when token-less (mirrors AgentWsClient). ---

    private fun statusClient(token: String) = StatusMuxClient(
        client = HttpClient(CIO),
        httpBaseUrl = "http://h:1",
        wsBaseUrl = "ws://h:1",
        token = token,
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun statusMux_tokenless_omitsTokenQuery_present_includesIt() {
        // token-less (deployed web SPA) → NO `?token=` at all → cookie authenticates the handshake.
        assertEquals("ws://h:1/ws/status", statusClient("").statusUrl())
        // present (desktop/operator) → the token rides as before.
        assertEquals("ws://h:1/ws/status?token=op", statusClient("op").statusUrl())
    }

    // --- REST emitters: capture the outbound Authorization header against a real server. ---

    @Test
    fun agentManagement_tokenless_sendsNoAuthorizationHeader_present_sendsBearer() = runBlocking {
        var captured: String? = "SENTINEL"
        val server = embeddedServer(Netty, port = 0) {
            routing { get("/api/agents") { captured = call.request.headers["Authorization"]; call.respondText("[]") } }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            // token-less → NO Authorization header (cookie path). MUT (unconditional Bearer) → "Bearer " → reds.
            HttpClient(CIO).use { c ->
                AgentManagementHttpRepository(c, "http://127.0.0.1:$port", token = "").list()
            }
            assertNull(captured, "token-less agent-management read must send no Authorization header")
            // positive control: a present token still rides as Bearer.
            HttpClient(CIO).use { c ->
                AgentManagementHttpRepository(c, "http://127.0.0.1:$port", token = "op").list()
            }
            assertEquals("Bearer op", captured)
        } finally {
            server.stop(100, 200)
        }
    }

    @Test
    fun agentLifecycle_tokenless_sendsNoAuthorizationHeader_present_sendsBearer() = runBlocking {
        var captured: String? = "SENTINEL"
        val server = embeddedServer(Netty, port = 0) {
            routing { post("/api/agents/{id}/start") { captured = call.request.headers["Authorization"]; call.respondText("{}") } }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            HttpClient(CIO).use { c ->
                AgentLifecycleRepository(c, "http://127.0.0.1:$port", operatorToken = "").start("backend")
            }
            assertNull(captured, "token-less lifecycle control must send no Authorization header")
            HttpClient(CIO).use { c ->
                AgentLifecycleRepository(c, "http://127.0.0.1:$port", operatorToken = "op").start("backend")
            }
            assertEquals("Bearer op", captured)
        } finally {
            server.stop(100, 200)
        }
    }
}
