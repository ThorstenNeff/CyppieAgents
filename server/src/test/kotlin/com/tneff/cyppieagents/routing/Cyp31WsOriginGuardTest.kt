package com.tneff.cyppieagents.routing

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-31 — the EXPLICIT WS-origin gate ([installWsOriginGuard]), defense-in-depth beyond CORS.
 *
 * The load-bearing tooth is [emptyAllowlist_foreignOrigin_refused_onEveryRoute]: it installs the guard with an
 * EMPTY allowlist and NO CORS — exactly the config where [installRestrictedCors] is a no-op and WS had zero
 * origin protection — and proves a foreign Origin is refused on EVERY `/ws` route. Mutation: drop the guard (or
 * its refusal) → the foreign upgrade completes → red. The complementary teeth pin the permit rule (native
 * no-Origin, same-origin, allowlisted) so the guard can't be "fixed" by simply denying everything.
 */
class Cyp31WsOriginGuardTest {

    private val allowed = "http://localhost:8080"

    /** All eight live ws upgrade paths (PlatformWiring) — the guard is app-wide, so it must hold on each. */
    private val wsPaths = listOf(
        "/ws/agent", "/ws/comm", "/ws/events", "/ws/lifecycle",
        "/ws/token-usage", "/ws/busy-state", "/ws/terminal-state", "/ws/terminal",
    )

    /** Install ONLY the guard (no CORS) — proves it stands alone, i.e. holds where CORS is a no-op. */
    private fun ApplicationTestBuilder.app(allowedOrigins: List<String>) = application {
        installWsOriginGuard(allowedOrigins)
        install(WebSockets)
        routing { wsPaths.forEach { p -> webSocket(p) { /* no-op: we only exercise the origin gate on the upgrade */ } } }
    }

    private fun ApplicationTestBuilder.wsClient() = createClient { install(ClientWebSockets) }

    /** A real WS handshake; true iff the upgrade succeeded (the block ran). */
    private suspend fun ApplicationTestBuilder.wsConnects(path: String, origin: String?): Boolean =
        runCatching {
            wsClient().webSocket(path, request = { if (origin != null) header(HttpHeaders.Origin, origin) }) {}
        }.isSuccess

    // ---- THE GAP: empty allowlist (CORS is a no-op) — the guard still refuses a foreign origin, every route ----

    @Test
    fun emptyAllowlist_foreignOrigin_refused_onEveryRoute() = testApplication {
        app(emptyList())
        wsPaths.forEach {
            assertFalse(wsConnects(it, "http://evil.example"), "empty allowlist: a foreign origin must be refused on $it (the CORS-no-op hole)")
        }
    }

    @Test
    fun emptyAllowlist_noOrigin_connects_onEveryRoute() = testApplication {
        app(emptyList())
        wsPaths.forEach {
            assertTrue(wsConnects(it, null), "a native client (no Origin) must still connect on $it — the non-browser path must not break")
        }
    }

    @Test
    fun emptyAllowlist_sameOrigin_connects() = testApplication {
        // A same-origin browser (the web app served FROM the server's own origin) must connect even with no
        // allowlist configured — else a same-origin deployment breaks. The test server's own origin is
        // http://localhost:80 (default Host), so an Origin equal to it is same-origin.
        app(emptyList())
        assertTrue(wsConnects("/ws/comm", "http://localhost:80"), "same-origin must be permitted even with an empty allowlist")
        assertTrue(wsConnects("/ws/comm", "http://localhost"), "same-origin (default port) must be permitted")
    }

    // ---- allowlisted origin connects; foreign refused (non-empty allowlist too) ----

    @Test
    fun allowlistedOrigin_connects_onEveryRoute() = testApplication {
        app(listOf(allowed))
        wsPaths.forEach { assertTrue(wsConnects(it, allowed), "an allowlisted origin must connect on $it") }
    }

    @Test
    fun foreignOrigin_refused_withNonEmptyAllowlist_onEveryRoute() = testApplication {
        app(listOf(allowed))
        wsPaths.forEach { assertFalse(wsConnects(it, "http://evil.example"), "a foreign origin must be refused on $it") }
    }

    @Test
    fun suffixSpoofAndMismatches_refused() = testApplication {
        app(listOf(allowed))
        assertFalse(wsConnects("/ws/comm", "http://localhost.evil.example:8080"), "suffix-spoof of the allowed host must not match")
        assertFalse(wsConnects("/ws/comm", "https://localhost:8080"), "scheme mismatch (https vs http-only allow) must not match")
        assertFalse(wsConnects("/ws/comm", "http://localhost:9999"), "port mismatch must not match")
    }

    // ---- normalization (deterministic, host-independent) ----

    @Test
    fun normalizeWsOrigin_elidesDefaultPorts_lowercasesHost_andRejectsMalformed() {
        assertEquals("http://localhost:80", normalizeWsOrigin("http://localhost"))
        assertEquals("http://localhost:80", normalizeWsOrigin("HTTP://LocalHost:80/"))
        assertEquals("https://app.example:443", normalizeWsOrigin("https://app.example"))
        assertEquals("http://app.example:8080", normalizeWsOrigin("http://app.example:8080"))
        // a suffix-spoof normalizes to a DISTINCT host → can never equal the real host
        assertEquals("http://localhost.evil.example:8080", normalizeWsOrigin("http://localhost.evil.example:8080"))
        assertNull(normalizeWsOrigin("not-an-origin"))
        assertNull(normalizeWsOrigin("http://"))
    }
}
