package com.tneff.cyppieagents.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.cookies.cookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-638 S7 / CYP-666 — the **same-origin SPA static-leg** (R3), the load-bearing precondition Team-2 hangs on: the
 * gateway serves the built SPA under the SAME origin as the `/api` + `/ws` data-plane and `/.ory/kratos/public`, so the httpOnly
 * `ory_kratos_session` cookie is set + auto-sent (which is what makes `/ws/agent`'s cookie-only auth work).
 *
 * The two properties that must hold **together** (a static-leg that trades one for the other is worse than none):
 *  1. the SPA is reachable same-origin (index + deep-link + asset), AND a real Kratos cookie roundtrip works over that
 *     one origin; **and simultaneously**
 *  2. the machine surface stays **404 at the edge** — the SPA fallback must NEVER mask the control-plane / `/ws/hub`
 *     deny with a `200 index.html` (deny before fallback).
 * Both are asserted in ONE test — split into two, the deny-claim would be only half-proven.
 *
 * Mutation-proven: drop the reserved-surface guard (serve the SPA for any unmatched path) → `/api/cp/challenge`,
 * `/ws/hub` return `200 index.html` → property (2) reds.
 */
class GatewayS7StaticLegTest {

    private val servers = mutableListOf<io.ktor.server.engine.EmbeddedServer<*, *>>()
    private var spaRoot: File? = null
    @AfterTest fun tearDown() {
        servers.forEach { it.stop(0, 0) }
        spaRoot?.parentFile?.deleteRecursively()
    }

    private fun start(block: io.ktor.server.application.Application.() -> Unit): Int {
        val s = embeddedServer(Netty, port = 0, module = block).start(wait = false)
        servers += s
        return runBlocking { s.engine.resolvedConnectors().first().port }
    }

    /** A temp SPA dir with an index + a nested asset, plus a SECRET file in its PARENT (outside the served root) that a
     *  path traversal would target — so the traversal tooth can prove no byte of it ever leaks. */
    private fun makeSpaDir(): File {
        val base = File.createTempFile("gw-spa", "").let { it.delete(); it.mkdirs(); it }
        val root = File(base, "dist").apply { mkdirs() }
        File(root, "index.html").writeText(SPA_INDEX)
        File(root, "assets").mkdirs()
        File(root, "assets/app.js").writeText(APP_JS)
        File(base, "SECRET.txt").writeText(SECRET) // sibling of the served root → only reachable via traversal
        spaRoot = root
        return root
    }

    /** Fake Kratos PUBLIC API: a self-service submit sets the session cookie (same as the real IdP). */
    private fun fakeKratos(): Int = start {
        routing {
            route("/self-service/{...}") {
                handle {
                    call.response.header(HttpHeaders.SetCookie, "ory_kratos_session=SESSION-xyz; Path=/; HttpOnly")
                    call.respondText("KRATOS-OK")
                }
            }
        }
    }

    /** Fake hub: `/api/auth/me` = 200 iff the session cookie is present (proves the same-origin cookie reached it). */
    private fun fakeHub(): Int = start {
        routing {
            get("/api/auth/me") {
                if (call.request.headers[HttpHeaders.Cookie]?.contains("ory_kratos_session=") == true) {
                    call.respondText("""{"role":"OPERATOR"}""")
                } else call.respond(HttpStatusCode.Unauthorized)
            }
            route("{...}") { handle { call.respondText("HUB") } }
        }
    }

    private fun gateway(hubPort: Int, kratosPort: Int, spa: File): Int = start {
        gatewayModule("http://127.0.0.1:$hubPort", kratosBaseUrl = "http://127.0.0.1:$kratosPort", spaDir = spa)
    }

    @Test
    fun spaServedSameOrigin_whileMachineSurfaceStays404_withCookieRoundtrip() {
        val gp = gateway(fakeHub(), fakeKratos(), makeSpaDir())
        val client = HttpClient(CIO) { install(HttpCookies) } // a browser-like cookie jar (one origin)
        runBlocking {
            // (1) SPA reachable same-origin: index, a client-side deep link (→ index fallback), and a real asset.
            client.get("http://127.0.0.1:$gp/").let {
                assertEquals(HttpStatusCode.OK, it.status, "GET / must serve the SPA")
                assertEquals(SPA_INDEX, it.bodyAsText(), "GET / must be the SPA index")
            }
            client.get("http://127.0.0.1:$gp/dashboard/agents").let {
                assertEquals(HttpStatusCode.OK, it.status, "a client-side deep link must fall back to index.html (SPA routing)")
                assertEquals(SPA_INDEX, it.bodyAsText(), "deep link → SPA index")
            }
            client.get("http://127.0.0.1:$gp/assets/app.js").let {
                assertEquals(HttpStatusCode.OK, it.status, "a real SPA asset must be served")
                assertEquals(APP_JS, it.bodyAsText(), "the asset bytes must be served verbatim")
            }
            // (2) SIMULTANEOUSLY the machine surface stays 404 at the edge — NOT masked by a 200 index.html.
            for (machine in listOf("/api/cp/challenge", "/api/cp/admit", "/ws/hub", "/mcp/hub")) {
                val r = client.get("http://127.0.0.1:$gp$machine")
                assertEquals(HttpStatusCode.NotFound, r.status, "$machine must stay 404 at the edge (SPA must NOT mask the deny)")
                assertFalse(r.bodyAsText().contains(SPA_INDEX), "$machine must NOT be served the SPA index (deny before fallback)")
            }
            // (1b) the real cookie roundtrip over that one origin: Kratos login sets ory_kratos_session same-origin →
            //      it auto-forwards → the hub's whoami sees it → 200. This is what the whole R3 requirement buys.
            assertEquals(HttpStatusCode.OK, client.post("http://127.0.0.1:$gp/.ory/kratos/public/self-service/login?flow=x").status)
            val jar = client.cookies("http://127.0.0.1:$gp")
            assertTrue(jar.any { it.name == "ory_kratos_session" }, "the session cookie is set same-origin as the SPA (got: $jar)")
            assertEquals(HttpStatusCode.OK, client.get("http://127.0.0.1:$gp/api/auth/me").status, "same-origin cookie → whoami 200")
        }
        client.close()
    }

    /**
     * ★ CYP-638 S7 — the MANDATORY path-traversal tooth (F5 lesson, verbatim). The static-leg is the gateway's FIRST
     * disk-serving surface. Probed over a **raw socket** because a normal HTTP client normalizes `..` in the URL and
     * would HIDE the bug. Every encoded-traversal form must **404 and never emit a byte** of the SECRET file that sits
     * outside the served root.
     *
     * Mutation-proven: resolve the file on the decoded-but-UN-normalized path (skip the `..`-reject) → the traversal
     * escapes the root and the SECRET leaks → this reds.
     */
    @Test
    fun encodedTraversal_neverEscapesTheSpaDir_norEmitsAByte() {
        val gp = gateway(fakeHub(), fakeKratos(), makeSpaDir())
        val traversals = listOf(
            "/%2e%2e%2fSECRET.txt",                     // /../SECRET.txt
            "/..%2fSECRET.txt",                         // /../SECRET.txt (bare ..)
            "/%2e%2e%2f%2e%2e%2fSECRET.txt",            // /../../SECRET.txt
            "/assets%2f%2e%2e%2f%2e%2e%2fSECRET.txt",   // /assets/../../SECRET.txt
            "/%2e%2e/SECRET.txt",                        // /..​/SECRET.txt (mixed)
        )
        for (t in traversals) {
            val (status, body) = rawGet(gp, t)
            assertFalse(body.contains(SECRET), "traversal [$t] leaked bytes of the out-of-root SECRET file")
            assertEquals(404, status, "traversal [$t] must 404 at the edge (never fall back to index.html)")
        }
        // positive control: a legit asset under the root is still served (the guard must not over-block).
        val (okStatus, okBody) = rawGet(gp, "/assets/app.js")
        assertEquals(200, okStatus, "a legit asset must still be served")
        assertTrue(okBody.contains(APP_JS), "the legit asset bytes are served")
    }

    /** Raw-socket GET: send the request line VERBATIM (no client-side `..` normalization) and read status + body. */
    private fun rawGet(port: Int, rawPath: String): Pair<Int, String> {
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().apply {
                write("GET $rawPath HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray()); flush()
            }
            val text = s.getInputStream().readBytes().decodeToString()
            val status = text.lineSequence().firstOrNull()?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: -1
            val body = text.substringAfter("\r\n\r\n", "")
            return status to body
        }
    }

    private companion object {
        const val SPA_INDEX = "<!doctype html><title>Cyppie SPA</title><div id=app></div>"
        const val APP_JS = "console.log('cyppie-spa-bundle');"
        const val SECRET = "TOP-SECRET-OUT-OF-ROOT-BYTES"
    }
}
