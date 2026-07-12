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
import com.tneff.cyppieagents.routing.installPlatform
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
 * QA CYP-459 — **independent third-assurance: the FULL 10 spoof families (A–J) against the REAL route surface.**
 *
 * Backend's [Cyp459RealRouteT2Test] proves the invariant faithfully with representative markers (family I + the WS
 * family G). This is the Tester's *independent* exhaustive enumeration from `CYP-458-loopback-bridge-attack-plan.md`
 * §2.1: one representative marker from EACH family A–J driven over the real `127.0.0.1` loopback bridge into the
 * ACTUAL [installPlatform] routes. For every family the assertion is the SAME as the differential zero-privilege
 * claim: a forged marker with **no real credential** yields the identical **401** (HTTP) / **close(1008)** (WS) as a
 * bare request — the marker grants ZERO, no oracle. A real bearer over the SAME loopback IS served (non-vacuous),
 * proving the route reads its principal ONLY from the real credential and honors NO transport assertion.
 */
class QaCyp459TenFamilyT2Test {

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

    private fun startRealPlatform(): Pair<Int, String> {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        cleanups += { scope.cancel() }
        val agentToken = "tok-backend"
        val booted = BootOrchestrator(
            PlatformConfig(
                RepoConfig("git@github.com:org/repo.git", "main"),
                agents = listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("backend", "BE", Role.WORKER)),
            ),
            Secrets(mapOf(agentToken to "backend"), operatorToken = "tok-op", apiKey = null),
            WorktreeManager(FakeGit(), Files.createTempDirectory("qa-cyp459-rt").toFile()),
            FakeSpawner(),
            scope,
        ).boot()
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { installPlatform(booted) }.start(wait = false)
        cleanups += { server.stop(0, 0) }
        val port = runBlocking { server.engine.resolvedConnectors() }.first().port
        return port to agentToken
    }

    private fun driveHttp(port: Int, request: String): String = runBlocking {
        withTimeout(30_000) {
            val tunnel = ControllableTunnel()
            val job = launch(Dispatchers.IO) { LoopbackBridge(port).bridge(tunnel) }
            tunnel.deliver(request.encodeToByteArray())
            job.join()
            val sb = StringBuilder()
            while (true) {
                val chunk = tunnel.outbound.receiveCatching().getOrNull() ?: break
                sb.append(chunk.decodeToString())
            }
            sb.toString()
        }
    }

    private fun httpGet(path: String, headers: List<String>): String =
        (listOf("GET $path HTTP/1.1", "Host: 127.0.0.1", "Connection: close") + headers).joinToString("\r\n") + "\r\n\r\n"
    private fun statusLine(resp: String) = resp.lineSequence().firstOrNull()?.trim().orEmpty()

    /**
     * Families A–J from the attack plan §2.1, one representative marker each. Every entry is an OTHERWISE-uncredentialed
     * request; the family label documents which attacker class it stands for. Family H (loopback-peer-as-auth) is the
     * empty-marker case: being on 127.0.0.1 must itself grant nothing.
     */
    private val families: List<Pair<String, List<String>>> = listOf(
        "A principal-injection header" to listOf("X-Forwarded-User: operator", "X-CP-Principal: operator", "X-Remote-User: operator"),
        "B internal/trusted flag" to listOf("X-Internal: true", "X-CP-Internal: true", "X-Trusted: true"),
        "C auth-status claim" to listOf("X-CP-Auth: passed", "X-Auth-Status: authenticated", "X-CP-Session-Verified: true"),
        "D role/privilege elevation" to listOf("X-CP-Role: OPERATOR", "X-Roles: operator,admin", "X-Scope: *"),
        "E forwarded-origin/proxy-trust" to listOf("X-Forwarded-For: 127.0.0.1", "X-Real-IP: 127.0.0.1", "Forwarded: for=127.0.0.1;by=trusted"),
        "F sentinel credential" to listOf("Authorization: Bearer internal"),
        "G cookie/session forgery (HTTP)" to listOf("Cookie: cp_session=forged; authenticated=true; cp_principal=operator"),
        "H loopback-peer-as-auth (no marker)" to emptyList(),
        "I frame/header forgery" to listOf("X-Already-Authenticated: true", "X-Local-Request: true"),
        "J parser-ambiguity (dup + case)" to listOf("X-Forwarded-User: legit", "X-Forwarded-User: attacker", "x-cp-principal: operator"),
    )

    @Test
    fun allTenFamilies_overRealLoopbackRoute_rejected_uniform401_withPositiveControl() {
        val (port, token) = startRealPlatform()

        // baseline: bare no-credential request is 401 — the differential reference every family must match.
        val bareStatus = statusLine(driveHttp(port, httpGet("/api/agents", emptyList())))
        assertTrue("401" in bareStatus, "baseline bare request is 401: '$bareStatus'")

        // every family: a forged marker with NO real credential → the SAME 401 as bare (marker grants zero, no oracle).
        for ((label, headers) in families) {
            val status = statusLine(driveHttp(port, httpGet("/api/agents", headers)))
            assertTrue(
                "401" in status,
                "family [$label] over the real loopback route must be rejected identically (401), not honored: '$status'",
            )
        }

        // ★ positive control (non-vacuous): a REAL agent bearer over the SAME loopback IS served — proves the route
        // reads its principal ONLY from the credential, and the uniform 401 above is a real gate, not a dead route.
        val authed = statusLine(driveHttp(port, httpGet("/api/agents", listOf("Authorization: Bearer $token"))))
        assertTrue("200" in authed, "a real agent bearer over the SAME loopback is served 200: '$authed'")
    }

    // ---- family G over the actual WS-upgrade surface (session forgery on /ws/agent) ----

    private fun driveWs(port: Int, request: String, windowMs: Long = 3_000): ByteArray = runBlocking {
        val tunnel = ControllableTunnel()
        val job = launch(Dispatchers.IO) { LoopbackBridge(port).bridge(tunnel) }
        tunnel.deliver(request.encodeToByteArray())
        val buf = ArrayList<Byte>()
        withTimeoutOrNull(windowMs) {
            while (true) {
                val chunk = tunnel.outbound.receiveCatching().getOrNull() ?: break
                chunk.forEach { buf.add(it) }
            }
        }
        tunnel.close()
        job.cancel()
        buf.toByteArray()
    }

    private fun wsUpgrade(path: String, headers: List<String>): String =
        (listOf(
            "GET $path HTTP/1.1", "Host: 127.0.0.1", "Upgrade: websocket", "Connection: Upgrade",
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==", "Sec-WebSocket-Version: 13",
        ) + headers).joinToString("\r\n") + "\r\n\r\n"

    private fun containsClose1008(bytes: ByteArray): Boolean {
        for (i in 0..bytes.size - 4) {
            if (bytes[i] == 0x88.toByte() && bytes[i + 2] == 0x03.toByte() && bytes[i + 3] == 0xF0.toByte()) return true
        }
        return false
    }

    @Test
    fun familyG_wsUpgrade_forgedSessionCookie_rejectedClose1008_withPositiveControl() {
        val (port, token) = startRealPlatform()

        // family G on the WS surface: a forged session cookie + already-auth marker, NO token → close(1008).
        val forged = driveWs(
            port,
            wsUpgrade("/ws/agent?agentId=backend", listOf("Cookie: cp_session=forged; authenticated=true", "X-Already-Authenticated: true")),
        )
        assertTrue(containsClose1008(forged), "a forged-session WS-upgrade with no token is closed 1008 by the real WS auth")

        // differential: bare no-token upgrade → the SAME close(1008).
        val bare = driveWs(port, wsUpgrade("/ws/agent?agentId=backend", emptyList()))
        assertTrue(containsClose1008(bare), "a bare no-token WS-upgrade is closed 1008 too — identical reject, no oracle")

        // ★ positive control: a REAL agent token over the SAME WS surface is NOT closed 1008 (auth accepted).
        val authed = driveWs(port, wsUpgrade("/ws/agent?agentId=backend", listOf("Authorization: Bearer $token")))
        assertTrue(!containsClose1008(authed), "a real agent token over the SAME WS surface is NOT closed 1008 (auth accepted)")
    }
}
