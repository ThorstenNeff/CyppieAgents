package com.tneff.cyppieagents.auth

import com.tneff.cyppieagents.FakeGit
import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.BootedPlatform
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.installPlatform
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-188 / P1-1 + P1-2 — the **WebSocket + `/mcp` fail-closed net**. The two existing meta-tests
 * ([ProtectedRouteEnumerationTest], [MemberTier403MatrixTest]) both `filter startsWith("/api")`, so the entire
 * `/ws/` handshake surface and `/mcp/` were UNPROVEN — under the reverse-proxy basic-auth belt that was
 * covered; belt-off makes the app guard the sole backstop, so this net now carries them.
 *
 * It walks the REAL routing tree and asserts every `/ws/` and `/mcp/` route **fails closed with NO
 * credential**: a WS route must close (VIOLATED_POLICY) **before delivering any app frame**; a `/mcp/` route
 * must answer 401/403 **before the body is acted on**. This asserts ONLY the security invariant (no-cred →
 * reject) — NOT the per-route tier — so it stays green when CYP-188 B later admits verified human sessions on
 * the read-only WS (a session is a valid credential, not a no-cred).
 */
class WsMcpAuthzNetTest {

    @Test
    fun everyWsRoute_failsClosed_withoutCredential() = testApplication {
        lateinit var app: Application
        application {
            app = this
            installPlatform(bootFake(), settingsClient = KratosSettingsClient("http://localhost:1"))
        }
        startApplication()
        val ws = enumerate(app.routing { }).filter { it.method == "GET" && it.path.startsWith("/ws/") }.map { it.path }
        // Sanity: the walk actually found the WS surface (comm, agent, hub, events, lifecycle) — a silent
        // empty result would be a false pass, and a NEW /ws/* route is auto-included here.
        assertTrue(ws.size >= 5, "route walk found too few /ws routes (${ws.size}: $ws) — webSocket-route enumeration broken?")

        val leaks = probeWsForLeaks(ws)
        assertTrue(leaks.isEmpty(), "these /ws routes delivered an app frame with NO credential (add a fail-closed guard): $leaks")
    }

    @Test
    fun everyMcpRoute_failsClosed_withoutCredential() = testApplication {
        lateinit var app: Application
        application {
            app = this
            installPlatform(bootFake(), settingsClient = KratosSettingsClient("http://localhost:1"))
        }
        startApplication()
        val mcp = enumerate(app.routing { }).filter { it.path.startsWith("/mcp/") }
        assertTrue(mcp.isNotEmpty(), "route walk found no /mcp routes — enumeration broken?")

        val leaks = mutableListOf<String>()
        for (ep in mcp) {
            val resp: HttpResponse = client.request(ep.path) { method = HttpMethod.parse(ep.method) }
            if (resp.status != HttpStatusCode.Unauthorized && resp.status != HttpStatusCode.Forbidden) {
                leaks += "${ep.method} ${ep.path} → ${resp.status}"
            }
        }
        assertTrue(leaks.isEmpty(), "these /mcp routes did NOT fail closed without a credential: $leaks")
    }

    /**
     * TEETH (durable, no manual mutation): mount a deliberately UNGUARDED `/ws/leak` (emits a frame) and
     * `/mcp/leak` (200) and assert the net flags BOTH. If this ever fails, the walk/probe has gone blind and the
     * primary tests are false-passes — the tripwire on the tripwire.
     */
    @Test
    fun scanDetectsAnUnguardedWsAndMcpRoute() = testApplication {
        lateinit var app: Application
        application {
            app = this
            installPlatform(bootFake(), settingsClient = KratosSettingsClient("http://localhost:1"))
            routing {
                webSocket("/ws/leak") { send(Frame.Text("open")); for (f in incoming) {} }
                post("/mcp/leak") { call.respondText("open") }
            }
        }
        startApplication()

        val ws = enumerate(app.routing { }).filter { it.method == "GET" && it.path.startsWith("/ws/") }.map { it.path }
        assertTrue(probeWsForLeaks(ws).any { it.contains("/ws/leak") }, "the WS scan failed to detect the unguarded /ws/leak")

        val mcp = enumerate(app.routing { }).filter { it.path.startsWith("/mcp/") }
        val mcpLeaks = mcp.filter { ep ->
            client.request(ep.path) { method = HttpMethod.parse(ep.method) }.status.let {
                it != HttpStatusCode.Unauthorized && it != HttpStatusCode.Forbidden
            }
        }
        assertTrue(mcpLeaks.any { it.path == "/mcp/leak" }, "the /mcp scan failed to detect the unguarded /mcp/leak")
    }

    /** Connect to each WS path with NO credential; a fail-closed route closes before any Text (app) frame. */
    private suspend fun ApplicationTestBuilder.probeWsForLeaks(paths: List<String>): List<String> {
        val wsClient = createClient { install(WebSockets) }
        val leaks = mutableListOf<String>()
        for (path in paths) {
            val leaked = try {
                var sawAppFrame = false
                wsClient.webSocket(path) {
                    // A guarded route closes (VIOLATED_POLICY) immediately → the channel is closed and the first
                    // receive yields null/Close, never a Text app frame (e.g. the channels/status snapshot).
                    val first = withTimeoutOrNull(3_000) { incoming.receiveCatching().getOrNull() }
                    if (first is Frame.Text) sawAppFrame = true
                }
                sawAppFrame
            } catch (e: Exception) {
                false // handshake rejected / connection closed → fail-closed
            }
            if (leaked) leaks += "$path (app frame without credential)"
        }
        return leaks
    }

    // ---- routing-tree enumeration (mirrors ProtectedRouteEnumerationTest) ----

    private data class Endpoint(val method: String, val path: String)

    private fun enumerate(root: RoutingNode): List<Endpoint> {
        val out = mutableListOf<Endpoint>()
        fun visit(node: RoutingNode) {
            (node.selector as? HttpMethodRouteSelector)?.let { out += Endpoint(it.method.value, pathOf(node)) }
            node.children.forEach { visit(it) }
        }
        visit(root)
        return out
    }

    private fun pathOf(node: RoutingNode): String {
        val segments = ArrayDeque<String>()
        var cur: RoutingNode? = node
        while (cur != null) {
            when (val s = cur.selector) {
                is PathSegmentConstantRouteSelector -> segments.addFirst(s.value)
                is PathSegmentParameterRouteSelector -> segments.addFirst("{${s.name}}")
                else -> {}
            }
            cur = cur.parent
        }
        return "/" + segments.joinToString("/")
    }

    // ---- fake boot (mirrors ProtectedRouteEnumerationTest.bootFake) ----

    private class FakeProcess : AgentProcess {
        override val stdoutLines: Flow<String> = emptyFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }

    private fun bootFake(): BootedPlatform {
        val config = PlatformConfig(
            RepoConfig("git@github.com:org/repo.git", "main"),
            agents = listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("backend", "BE", Role.WORKER)),
        )
        val secrets = Secrets(mapOf("tok-backend" to "backend"), operatorToken = "tok-op", apiKey = null)
        val gitRoot = Files.createTempDirectory("wsmcp-net-test").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> FakeProcess() }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
    }
}
