package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.FakeGit
import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.installPlatform
import com.tneff.cyppieagents.routing.installTunnelGodTokenGuardOnPorts
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-427 (Phase-2 / M2) — the **tunnel-scoped connector + God-token reject** end-to-end over the REAL routes and the
 * REAL [LoopbackBridge]. Two Netty connectors on ONE Application (one shared platform / store set); the bridge pumps
 * into the tunnel-scoped connector, on which [installTunnelGodTokenGuard] refuses the static machine operator token.
 *
 * Teeth:
 *  - [godToken_bearer_overTunnelConnector_rejected401] — the static operator token (Authorization bearer) is 401'd on
 *    the tunnel connector.
 *  - [godToken_bearer_overPublicConnector_stillServed200] — the SAME token still works on the PUBLIC connector (the
 *    guard is port-scoped, not global — non-vacuous).
 *  - [agentToken_overTunnelConnector_accepted200] — an agent (read-tier) token IS accepted on the tunnel connector
 *    (ONLY the God token is refused — the Kratos/agent axes are untouched; non-vacuous).
 *  - [godTokenQueryParam_overTunnelConnector_rejected401] — the God token on the `?token=` WS/query fallback is also
 *    refused (both axes the auth layer honors — matches the E2E `/ws/events?token=$OP` path).
 *  - [truncatedRequest_overBridge_actionsNoPartialMutation] — a truncated request over the tunnel actions NO partial
 *    mutation (the server leg of CR3-② F1), and hub responses are self-delimiting (Content-Length/chunked), not
 *    close-delimited (Reviewer caveat a).
 */
class Cyp427TunnelScopedListenerTest {

    private val cleanups = mutableListOf<() -> Unit>()
    @AfterTest fun tearDown() { cleanups.asReversed().forEach { runCatching { it() } } }

    private class FakeProcess : AgentProcess {
        override val stdoutLines: Flow<String> = emptyFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }
    private class FakeSpawner : ProcessSpawner {
        override fun spawn(command: List<String>, cwd: File, env: Map<String, String>): AgentProcess = FakeProcess()
    }

    private class ControllableTunnel : ServerNoiseTunnel {
        private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
        val outbound = Channel<ByteArray>(Channel.UNLIMITED)
        override val handshakeHash: ByteArray get() = ByteArray(32)
        override suspend fun receive(): ByteArray? = inbound.receiveCatching().getOrNull()
        override suspend fun send(plaintext: ByteArray) { outbound.trySend(plaintext) }
        override suspend fun close() { inbound.close(); outbound.close() }
        fun deliver(bytes: ByteArray) { inbound.trySend(bytes) }
    }

    private data class Platform(
        val publicPort: Int,
        val tunnelPort: Int,
        // CYP-536 WS6 C5 axis 1a: a SECOND tunnel-scoped connector, so the God-token guard is proven to reject over the
        // PORT-SET (all N tunnel ports), not just one fixed `tunnelPort`.
        val tunnelPort2: Int,
        val agentToken: String,
        val operatorToken: String,
    )

    /** Boot the REAL platform once, exposed on THREE loopback connectors ([0]=public, [1]/[2]=tunnel-scoped); the
     *  God-token guard is installed over the SET of both tunnel ports (CYP-536 port-SET discriminator). */
    private fun startTwoConnectorPlatform(): Platform {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        cleanups += { scope.cancel() }
        val agentToken = "tok-backend"
        val operatorToken = "tok-op"
        val booted = BootOrchestrator(
            PlatformConfig(
                RepoConfig("git@github.com:org/repo.git", "main"),
                agents = listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("backend", "BE", Role.WORKER)),
            ),
            Secrets(mapOf(agentToken to "backend"), operatorToken = operatorToken, apiKey = null),
            WorktreeManager(FakeGit(), Files.createTempDirectory("cyp427-tsl").toFile()),
            FakeSpawner(),
            scope,
        ).boot()
        // CYP-534: BOTH connectors are ephemeral (port=0); the guard reads the tunnel port via a supplier RESOLVED
        // AFTER the bind — no `ServerSocket(0)`-close→re-bind TOCTOU. resolvedConnectors() preserves declaration
        // order: [0]=public, [1]=tunnel. Requests only arrive after the holder is set, so the supplier is populated.
        val tunnelPortHolder = java.util.concurrent.atomic.AtomicInteger(-1)
        val tunnelPort2Holder = java.util.concurrent.atomic.AtomicInteger(-1)
        val server = embeddedServer(
            Netty,
            serverConfig {
                module {
                    installPlatform(booted)
                    // CYP-536: the guard discriminates on the SET of tunnel ports {t1, t2} (a positive allowlist), so a
                    // God token is refused on EVERY tunnel connector, never only the first. Ports resolved post-bind.
                    installTunnelGodTokenGuardOnPorts(
                        { setOf(tunnelPortHolder.get(), tunnelPort2Holder.get()) },
                        booted.tokenRegistry::isOperator,
                    )
                }
            },
        ) {
            connector { port = 0; host = "127.0.0.1" } // [0] public
            connector { port = 0; host = "127.0.0.1" } // [1] tunnel-scoped (guarded)
            connector { port = 0; host = "127.0.0.1" } // [2] tunnel-scoped #2 (also guarded — the port-SET tooth)
        }.start(wait = false)
        cleanups += { server.stop(0, 0) }
        val conns = runBlocking { server.engine.resolvedConnectors() }
        val publicPort = conns[0].port
        val tunnelPort = conns[1].port
        val tunnelPort2 = conns[2].port
        tunnelPortHolder.set(tunnelPort)
        tunnelPort2Holder.set(tunnelPort2)
        return Platform(publicPort, tunnelPort, tunnelPort2, agentToken, operatorToken)
    }

    // ── raw HTTP over the bridge ──
    private fun httpGet(path: String, headers: List<String>): String =
        (listOf("GET $path HTTP/1.1", "Host: 127.0.0.1", "Connection: close") + headers).joinToString("\r\n") + "\r\n\r\n"

    private fun wsUpgrade(path: String, headers: List<String>): String =
        (listOf(
            "GET $path HTTP/1.1", "Host: 127.0.0.1", "Upgrade: websocket", "Connection: Upgrade",
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==", "Sec-WebSocket-Version: 13",
        ) + headers).joinToString("\r\n") + "\r\n\r\n"

    private fun statusLine(resp: String) = resp.lineSequence().firstOrNull()?.trim().orEmpty()

    /** Pump [req] through the bridge to [port]; return the response bytes once the headers are complete (or on close/window). */
    private fun driveStatus(port: Int, req: String, windowMs: Long = 8_000): String = runBlocking {
        val tunnel = ControllableTunnel()
        val job = launch(Dispatchers.IO) { LoopbackBridge(port).bridge(tunnel) }
        val sb = StringBuilder()
        tunnel.deliver(req.encodeToByteArray())
        withTimeoutOrNull(windowMs) {
            while (true) {
                if (sb.contains("\r\n\r\n")) break // headers complete — the status line is available
                val chunk = tunnel.outbound.receiveCatching().getOrNull() ?: break // hub closed
                sb.append(chunk.decodeToString())
            }
        }
        tunnel.close(); job.cancel()
        sb.toString()
    }

    /** Pump [req] through the bridge to [port]; read the FULL response until the hub closes (needs `Connection: close`). */
    private fun driveFull(port: Int, req: String, windowMs: Long = 8_000): String = runBlocking {
        val tunnel = ControllableTunnel()
        val job = launch(Dispatchers.IO) { LoopbackBridge(port).bridge(tunnel) }
        val sb = StringBuilder()
        tunnel.deliver(req.encodeToByteArray())
        withTimeoutOrNull(windowMs) {
            while (true) {
                val chunk = tunnel.outbound.receiveCatching().getOrNull() ?: break
                sb.append(chunk.decodeToString())
            }
        }
        tunnel.close(); job.cancel()
        sb.toString()
    }

    @Test
    fun godToken_bearer_overTunnelConnector_rejected401() {
        val p = startTwoConnectorPlatform()
        val resp = driveStatus(p.tunnelPort, httpGet("/api/agents", listOf("Authorization: Bearer ${p.operatorToken}")))
        assertTrue("401" in statusLine(resp), "the static operator (God) token is refused on the tunnel connector: '${statusLine(resp)}'")
    }

    @Test
    fun godToken_bearer_overSecondTunnelConnector_rejected401() {
        // CYP-536 WS6 C5 axis 1a — the God token is refused on the SECOND tunnel port too, proving the guard checks the
        // PORT-SET (membership across all N tunnel connectors), not just the first `tunnelPort`. A single-port equality
        // discriminator would 200 here (the vulnerability the frozen axis closes: an unguarded Nth tunnel port).
        val p = startTwoConnectorPlatform()
        val resp = driveStatus(p.tunnelPort2, httpGet("/api/agents", listOf("Authorization: Bearer ${p.operatorToken}")))
        assertTrue("401" in statusLine(resp), "the God token is refused on the 2nd tunnel connector (port-SET guard): '${statusLine(resp)}'")
    }

    @Test
    fun godToken_bearer_overPublicConnector_stillServed200() {
        val p = startTwoConnectorPlatform()
        val resp = driveStatus(p.publicPort, httpGet("/api/agents", listOf("Authorization: Bearer ${p.operatorToken}")))
        assertTrue("200" in statusLine(resp), "the SAME God token still works on the public connector (guard is port-scoped): '${statusLine(resp)}'")
    }

    @Test
    fun agentToken_overTunnelConnector_accepted200() {
        val p = startTwoConnectorPlatform()
        val resp = driveStatus(p.tunnelPort, httpGet("/api/agents", listOf("Authorization: Bearer ${p.agentToken}")))
        assertTrue("200" in statusLine(resp), "an agent (read-tier) token is accepted on the tunnel connector — only the God token is refused: '${statusLine(resp)}'")
    }

    @Test
    fun godTokenQueryParam_overTunnelConnector_rejected401() {
        val p = startTwoConnectorPlatform()
        // The E2E carries the operator token as /ws/events?token=$OP; a bearer-only guard would leak this WS axis.
        val resp = driveStatus(p.tunnelPort, wsUpgrade("/ws/events?token=${p.operatorToken}", emptyList()))
        assertTrue("401" in statusLine(resp), "the God token via ?token= is refused on the tunnel connector: '${statusLine(resp)}'")
        assertTrue("101" !in statusLine(resp), "the WS upgrade never switched protocols (the ?token= God token did not authenticate)")
    }

    @Test
    fun godTokenPredicate_isTrueOnlyForStaticToken_notSessionsOrAgents() {
        // (iii) The guard rejects EXACTLY `isOperator == true`. That is true ONLY for the one static operator string —
        // a CP-scoped Kratos session token (a different random string, which authenticates on the X-Session-Token /
        // cookie axis as a Human operator, not MachineOperator) is `isOperator == false`, so the guard passes it. Proven
        // at the predicate so the guard can never 401 the legitimate remote operator's session, nor mistake it for the God token.
        val registry = TokenRegistry(mapOf("tok-backend" to "backend"), operatorToken = "tok-op", loopbackPosture = true)
        assertTrue(registry.isOperator("tok-op"), "the static operator token IS the God token")
        assertTrue(!registry.isOperator("kratos-session-abc123"), "a CP-scoped Kratos session token is NOT the God token — the guard passes it")
        assertTrue(!registry.isOperator("tok-backend"), "an agent token is NOT the God token")
        assertTrue(!registry.isOperator(null), "no credential is not the God token")
    }

    @Test
    fun truncatedRequest_overBridge_actionsNoPartialMutation() = runBlocking {
        val p = startTwoConnectorPlatform()
        // A create that CLAIMS a full body (Content-Length) but delivers only a fragment, then the tunnel DROPS
        // mid-body → the bridge RSTs the hub socket → Ktor never finishes reading the body → the handler never mutates.
        // (POST /api/agents is operator-gated, so we run it on the PUBLIC connector where the operator token authenticates
        //  — this tooth is about the BRIDGE's request-truncation safety, orthogonal to the God-token reject.)
        val partialBody = """{"id":"ghost","na"""
        val req = "POST /api/agents HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
            "Authorization: Bearer ${p.operatorToken}\r\nContent-Type: application/json\r\n" +
            "Content-Length: 200\r\n\r\n" + partialBody // Content-Length >> the fragment delivered
        val tunnel = ControllableTunnel()
        val job = launch(Dispatchers.IO) { LoopbackBridge(p.publicPort).bridge(tunnel) }
        tunnel.deliver(req.encodeToByteArray())
        tunnel.close() // enqueued AFTER the fragment → receive() yields the fragment, then null → RST (mid-request)
        withTimeout(10_000) { job.join() } // the bridge pumps the fragment, RSTs on the null, and returns

        // The mutation must not have happened: a COMPLETE request on the public connector shows the roster WITHOUT "ghost".
        val list = driveFull(p.publicPort, httpGet("/api/agents", listOf("Authorization: Bearer ${p.operatorToken}")))
        assertTrue("200" in statusLine(list), "the platform serves a complete request (baseline live): '${statusLine(list)}'")
        assertTrue("backend" in list, "the baseline roster is present — the GET is meaningful, not empty/erroring")
        assertTrue("ghost" !in list, "the TRUNCATED create actioned NO partial mutation — 'ghost' was never created")
        // Reviewer caveat (a): hub REST responses are self-delimiting (Content-Length / chunked), NOT close-delimited,
        // so truncation is detectable on both legs (server rejects a short request body; client detects a short response).
        assertTrue(
            "Content-Length" in list || "Transfer-Encoding: chunked" in list,
            "hub responses are self-delimiting (Content-Length/chunked), not close-delimited",
        )
    }
}
