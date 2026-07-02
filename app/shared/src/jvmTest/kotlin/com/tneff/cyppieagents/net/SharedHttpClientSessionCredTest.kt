package com.tneff.cyppieagents.net

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-188 P2a — the shared HTTP/WS client's session-credential seam. A signed-in user with NO operator token
 * must authenticate its data reads via the Kratos session; the seam sends the **native** `X-Session-Token` header
 * from the auth session store. The load-bearing invariants (backend-confirmed 2026-07-02):
 *  - NATIVE: `X-Session-Token` present → `requireCommReader` accepts the human session (else the empty `Bearer `
 *    401s the whole app);
 *  - **single credential source**: the client sends the header XOR a cookie, NEVER both (both poison Kratos
 *    whoami → 500, the P1 dual-header bug). This client owns no cookie jar, and injects the header ONLY when a
 *    native token exists; a browser session (no native token → header absent) rides its same-origin cookie alone.
 */
class SharedHttpClientSessionCredTest {

    private fun withProbe(block: suspend (baseUrl: String) -> Unit) = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            routing {
                // Models a session-gated read: 200 ONLY for the native header + NO cookie (single source); else 401.
                get("/probe") {
                    val token = call.request.header("X-Session-Token")
                    val cookie = call.request.header("Cookie")
                    if (token == "sess-tok" && cookie == null) {
                        call.respondText("ok", ContentType.Text.Plain)
                    } else {
                        call.respondText("unauthorized", ContentType.Text.Plain, HttpStatusCode.Unauthorized)
                    }
                }
                // Echoes whether ANY X-Session-Token header rode along (for the browser/no-session case).
                get("/echo-header") {
                    call.respondText((call.request.header("X-Session-Token") != null).toString(), ContentType.Text.Plain)
                }
            }
        }.start()
        val port = server.engine.resolvedConnectors().first().port
        try {
            block("http://127.0.0.1:$port")
        } finally {
            server.stop()
        }
    }

    @Test
    fun read_nativeSession_sendsXSessionTokenHeader_singleSource_authOk() = withProbe { base ->
        // ⭐ Load-bearing: the session-only user's read carries X-Session-Token (and NO cookie) → the gated server
        // accepts it. A mutation that drops the DefaultRequest seam sends no header → 401 → this is the only RED.
        val client = sharedWsHttpClient(sessionToken = { "sess-tok" })
        try {
            val resp = client.get("$base/probe")
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals("ok", resp.bodyAsText())
        } finally {
            client.close()
        }
    }

    @Test
    fun read_noNativeToken_sendsNoHeader_soBrowserCookieIsSoleSource() = withProbe { base ->
        // Browser/no-session case: no native token ⇒ NO X-Session-Token header — so a browser rides its same-origin
        // ory_kratos_session cookie as the SOLE credential (never header+cookie together → no 500). A mutation that
        // always injects a header would poison the browser's dual-source invariant → this asserts "false" → RED.
        val client = sharedWsHttpClient(sessionToken = { null })
        try {
            assertEquals("false", client.get("$base/echo-header").bodyAsText())
        } finally {
            client.close()
        }
    }

    @Test
    fun read_liveTokenRotation_isPickedUpPerRequest() = withProbe { base ->
        // The seam reads the provider LIVE per request (DefaultRequest), so a token captured AFTER client creation
        // (e.g. login completes) is honored — not frozen at construction. Starts null (401), then present (200).
        var token: String? = null
        val client = sharedWsHttpClient(sessionToken = { token })
        try {
            assertEquals(HttpStatusCode.Unauthorized, client.get("$base/probe").status)
            token = "sess-tok"
            assertEquals(HttpStatusCode.OK, client.get("$base/probe").status)
        } finally {
            client.close()
        }
    }
}
