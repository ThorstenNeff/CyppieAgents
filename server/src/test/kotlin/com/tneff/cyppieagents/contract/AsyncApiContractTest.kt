package com.tneff.cyppieagents.contract

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
import io.ktor.server.application.Application
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-234a-2a — the generated **AsyncAPI (WS) document**: structure + the `/ws/hub` frontend-exclusion made
 * TESTABLE (design §2.5), and the **bidirectional WS-channel drift-test** — every real WS socket is EITHER a
 * frontend channel OR a KNOWN exclusion (no silently-missing channel; no phantom channel). The real-socket
 * inventory is **DERIVED from the actual routing tree** (same mechanism as `ProtectedRouteEnumerationTest`), so
 * a new `webSocket(...)` is discovered automatically — never a hand-maintained-driftable list (PO-Assistant).
 */
class AsyncApiContractTest {

    private fun doc() = ContractGenerator.asyncApi()
    private fun channels() = doc()["channels"] as JsonObject
    private fun schemas() = (doc()["components"] as JsonObject)["schemas"] as JsonObject
    private fun messages() = (doc()["components"] as JsonObject)["messages"] as JsonObject

    @Test
    fun asyncApi_hasTheFrontendChannels_withMessagesRefencingGeneratedSchemas() {
        val ch = channels()
        assertEquals(setOf("/ws/comm", "/ws/events", "/ws/lifecycle", "/ws/token-usage", "/ws/agent"), ch.keys, "exactly the frontend channels")
        val comm = ch["/ws/comm"] as JsonObject
        val sub = ((comm["subscribe"] as JsonObject)["message"] as JsonObject)["\$ref"] as JsonPrimitive
        assertEquals("#/components/messages/CommWsServerEvent", sub.content)
        assertTrue((comm["publish"] as JsonObject).containsKey("message"), "client→server publish present")
        for ((_, m) in messages()) {
            val ref = ((m as JsonObject)["payload"] as JsonObject)["\$ref"] as JsonPrimitive
            assertTrue(schemas().containsKey(ref.content.substringAfterLast('/')), "message payload resolves to a generated schema")
        }
        assertFalse((ch["/ws/lifecycle"] as JsonObject).containsKey("publish"), "lifecycle is a one-way status feed")
        assertFalse((ch["/ws/token-usage"] as JsonObject).containsKey("publish"), "token-usage is a one-way status feed (CYP-316)")
    }

    @Test
    fun wsHub_isExcluded_notSilentlyMissing() {
        assertFalse(channels().containsKey("/ws/hub"), "/ws/hub is NOT a frontend channel")
        assertTrue("/ws/hub" in ContractGenerator.EXCLUDED_WS_PATHS, "…but it is a KNOWN, explicit exclusion (connector wire)")
    }

    @Test
    fun wsChannelDrift_isBidirectional_derivedFromRealRouting() = testApplication {
        lateinit var app: Application
        application {
            app = this
            installPlatform(bootFake())
        }
        startApplication() // materialize the real routing tree

        // DERIVED from the live routing (a webSocket() registers as a GET /ws/… route) — NOT a hand-list.
        val actualWsSockets = enumerate(app.routing { }).map { it.path }.filter { it.startsWith("/ws") }.toSet()
        assertTrue(actualWsSockets.size >= 5, "ws enumeration found too few sockets (${actualWsSockets.size}) — walk broken?")
        val documented = channels().keys + ContractGenerator.EXCLUDED_WS_PATHS
        assertEquals(emptySet(), actualWsSockets - documented, "every DERIVED real WS socket is a channel or a known exclusion")
        assertEquals(emptySet(), documented - actualWsSockets, "no documented channel/exclusion without a real socket")
    }

    /**
     * CYP-234a-2a (Tester standing guard) — the REST-analog to `scanDetectsAnUnguardedApiRoute`: proves the
     * routing-DERIVED drift check actually catches a NEW real socket (the actual→documented axis that was
     * VACUUM while the socket inventory was a hand-maintained literal — PO NO-GO). Plant a REAL `webSocket`
     * that is NEITHER a frontend channel NOR a known exclusion; the derived drift set MUST flag it. If the
     * derivation ever regresses (stops discovering `webSocket(...)` routes), this reds — a permanent backstop,
     * not a one-shot probe.
     */
    @Test
    fun wsChannelDrift_detectsARealUnguardedSocket_provingTheDerivationIsLive() = testApplication {
        lateinit var app: Application
        application {
            app = this
            installPlatform(bootFake())
            routing { webSocket("/ws/leak") {} } // a REAL socket, no channel + no exclusion
        }
        startApplication()

        val actualWsSockets = enumerate(app.routing { }).map { it.path }.filter { it.startsWith("/ws") }.toSet()
        val documented = channels().keys + ContractGenerator.EXCLUDED_WS_PATHS
        assertTrue("/ws/leak" in actualWsSockets, "the planted webSocket is DISCOVERED by the routing derivation (not a hand-list)")
        assertTrue("/ws/leak" in (actualWsSockets - documented), "…and the drift check FLAGS it — an unguarded socket with no channel/exclusion is caught")
    }

    // ---- real routing-tree enumeration (mirrors ProtectedRouteEnumerationTest) ----

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
        val gitRoot = Files.createTempDirectory("contract-routeenum").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> FakeProcess() }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
    }
}
