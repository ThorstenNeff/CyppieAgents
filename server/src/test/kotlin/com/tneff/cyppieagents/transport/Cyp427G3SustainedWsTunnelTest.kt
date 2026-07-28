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
 * CYP-427 Phase-2 / M2 "G3" — **sustained bidirectional WebSocket over the Option-A loopback bridge**, plus the
 * **Op-Session-TTL teardown** characterized as defined behavior.
 *
 * The pre-existing bridge WS coverage ([Cyp459RealRouteT2Test], [QaCyp459TenFamilyT2Test]) proves only that the
 * HTTP/1.1 `Upgrade` handshake **survives the pump** (reaches 101) and that a server-sent `close(1008)` flows back —
 * i.e. handshake + one rejection frame within a ~3 s window. It does NOT prove that a **live, upgraded** WS carries
 * real application data frames in **steady state**, nor that both pump directions round-trip a **post-upgrade** frame,
 * nor what happens at the passive session lifetime. This test closes that gap so "the remote operator reaches the
 * FULL operator surface incl. the live WS feeds over the tunnel" is **proven, not asserted**:
 *
 *  1. [sustainedWs_postUpgradeServerDataFrame_flowsThroughPump] — a real post-upgrade **server→client application
 *     data frame** (`/ws/lifecycle` `AgentRunStateEvent` snapshot) streams through the pump after 101 (steady-state
 *     downstream, not just the handshake response).
 *  2. [sustainedWs_bidiPingPong_roundTripsThroughPump] — a **masked client PING** (RFC 6455 client frames MUST be
 *     masked) round-trips to a **server PONG** through the pump: BOTH directions carry a post-upgrade frame on the
 *     SAME still-open connection (the bidirectional, connection-stays-open proof).
 *  3. [opSessionTtl_tearsDownLiveIdleWs_activityIndependentHardCutoff] — the passive Op-Session-TTL
 *     ([Rr3AuthenticatedTunnelHandler.sessionTtlMs]) tears a **live, idle** WS down when it elapses (the tunnel is
 *     closed → the bridge RSTs the hub socket → the WS ends; re-auth required). This defines the hard, activity-
 *     INDEPENDENT lifetime a long-lived remote workspace WS is subject to — the reconnect the client must handle.
 *
 * Driven through the REAL [installPlatform] routes on a real 127.0.0.1 Netty listener via the REAL [LoopbackBridge],
 * exactly like the CYP-459 harness (an in-memory [ServerNoiseTunnel] feeds/captures the pumped bytes).
 */
class Cyp427G3SustainedWsTunnelTest {

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

    /** A controllable fake L2 tunnel: [deliver] client bytes to the bridge, capture the hub's response on [outbound]. */
    private class ControllableTunnel : ServerNoiseTunnel {
        private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
        val outbound = Channel<ByteArray>(Channel.UNLIMITED)
        override val handshakeHash: ByteArray get() = ByteArray(32)
        override suspend fun receive(): ByteArray? = inbound.receiveCatching().getOrNull()
        override suspend fun send(plaintext: ByteArray) { outbound.trySend(plaintext) }
        override suspend fun close() { inbound.close(); outbound.close() }
        fun deliver(bytes: ByteArray) { inbound.trySend(bytes) }
    }

    /** Boot the REAL platform on a loopback Netty listener; return (port, an agent bearer, the operator bearer). */
    private fun startRealPlatform(): Triple<Int, String, String> {
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
            WorktreeManager(FakeGit(), Files.createTempDirectory("cyp427-g3").toFile()),
            FakeSpawner(),
            scope,
        ).boot()
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { installPlatform(booted) }.start(wait = false)
        cleanups += { server.stop(0, 0) }
        val port = runBlocking { server.engine.resolvedConnectors() }.first().port
        return Triple(port, agentToken, operatorToken)
    }

    private fun wsUpgrade(path: String, headers: List<String>): String =
        (listOf(
            "GET $path HTTP/1.1", "Host: 127.0.0.1", "Upgrade: websocket", "Connection: Upgrade",
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==", "Sec-WebSocket-Version: 13",
        ) + headers).joinToString("\r\n") + "\r\n\r\n"

    private fun switchedProtocols(bytes: ByteArray) =
        "101" in bytes.decodeToString().lineSequence().firstOrNull().orEmpty()

    /** Accumulate [ch] chunks until [done] holds on the accumulated buffer, the channel closes, or [windowMs] elapses.
     *  Deterministic: it awaits the REAL bytes (a genuine failure is a timeout), never a fixed `delay` poll window. */
    private suspend fun collectUntil(ch: Channel<ByteArray>, windowMs: Long, done: (ByteArray) -> Boolean): ByteArray {
        val buf = ArrayList<Byte>()
        withTimeoutOrNull(windowMs) {
            while (true) {
                if (done(buf.toByteArray())) break
                val chunk = ch.receiveCatching().getOrNull() ?: break // channel closed (e.g. TTL teardown)
                chunk.forEach { buf.add(it) }
                if (done(buf.toByteArray())) break
            }
        }
        return buf.toByteArray()
    }

    private fun contains(hay: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
    private fun containsText(hay: ByteArray, s: String) = contains(hay, s.encodeToByteArray())
    /** A server→client PONG control frame with an empty payload: opcode 0x8A (FIN+pong), length 0x00. */
    private val PONG_EMPTY = byteArrayOf(0x8A.toByte(), 0x00)

    @Test
    fun sustainedWs_postUpgradeServerDataFrame_flowsThroughPump() = runBlocking {
        val (port, _, opToken) = startRealPlatform()
        val tunnel = ControllableTunnel()
        val job = launch(Dispatchers.IO) { LoopbackBridge(port).bridge(tunnel) }
        cleanups += { job.cancel() }

        tunnel.deliver(wsUpgrade("/ws/lifecycle", listOf("Authorization: Bearer $opToken")).encodeToByteArray())

        // A real post-upgrade server→client APPLICATION data frame (the AgentRunStateEvent snapshot) must stream
        // through the pump — steady-state WS traffic, not just the 101 handshake the existing tests stop at.
        val out = collectUntil(tunnel.outbound, 8_000) { containsText(it, "\"agentId\"") && containsText(it, "backend") }
        assertTrue(switchedProtocols(out), "the /ws/lifecycle upgrade reached 101 over the bridged tunnel")
        assertTrue(
            containsText(out, "\"agentId\"") && containsText(out, "\"runState\"") && containsText(out, "backend"),
            "a real post-upgrade AgentRunStateEvent snapshot data frame streamed through the pump (steady-state, not just the handshake)",
        )
        tunnel.close()
    }

    @Test
    fun sustainedWs_bidiPingPong_roundTripsThroughPump() = runBlocking {
        val (port, agentToken, _) = startRealPlatform()
        val tunnel = ControllableTunnel()
        val job = launch(Dispatchers.IO) { LoopbackBridge(port).bridge(tunnel) }
        cleanups += { job.cancel() }

        tunnel.deliver(wsUpgrade("/ws/comm", listOf("Authorization: Bearer $agentToken")).encodeToByteArray())
        val pre = collectUntil(tunnel.outbound, 6_000) { switchedProtocols(it) }
        assertTrue(switchedProtocols(pre), "the /ws/comm upgrade reached 101 over the bridged tunnel")

        // client → server: a masked, post-upgrade PING frame (opcode 0x89, MASK bit set, empty payload, 4-byte key).
        tunnel.deliver(byteArrayOf(0x89.toByte(), 0x80.toByte(), 0x12, 0x34, 0x56, 0x78))

        // server → client: the framework auto-answers with a PONG. Its arrival proves BOTH pump directions carry a
        // post-upgrade frame on the SAME still-open connection — a real bidirectional round-trip, not request/response.
        val pong = collectUntil(tunnel.outbound, 6_000) { contains(it, PONG_EMPTY) }
        assertTrue(
            contains(pong, PONG_EMPTY),
            "a masked client PING round-tripped to a server PONG through the pump (post-upgrade, bidirectional, connection stayed open)",
        )
        tunnel.close()
    }

    @Test
    fun opSessionTtl_tearsDownLiveIdleWs_activityIndependentHardCutoff() = runBlocking {
        val (port, _, opToken) = startRealPlatform()
        val ttlMs = 600L
        // Drive through the REAL Rr3AuthenticatedTunnelHandler (which owns the TTL). authorize = { true } isolates the
        // lifetime under test — the RR3 gate itself is proven by the CYP-469/525 suites; here we characterize teardown.
        val handler = Rr3AuthenticatedTunnelHandler(
            authorize = { "op" }, // CYP-882a: authorize yields the authenticated operatorId (non-null = granted)
            bridge = LoopbackBridge(port)::bridge,
            registry = TunnelSessionRegistry(),
            sessionTtlMs = ttlMs,
        )
        val tunnel = ControllableTunnel()
        val startNs = System.nanoTime()
        val handleJob = launch(Dispatchers.IO) { handler.handle(tunnel) }
        cleanups += { handleJob.cancel() }

        tunnel.deliver(wsUpgrade("/ws/lifecycle", listOf("Authorization: Bearer $opToken")).encodeToByteArray())

        // Read outbound until it CLOSES — the tunnel is closed by the passive Op-Session-TTL, closing this channel.
        // The accumulated buffer proves the WS was genuinely LIVE (101) before the TTL cut it, so this is a teardown
        // of a live idle connection, not a failed dial. (`done = { false }` → returns only on channel-close/timeout.)
        val all = collectUntil(tunnel.outbound, 10_000) { false }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

        assertTrue(switchedProtocols(all), "the idle WS was a LIVE upgraded connection (101) before the TTL fired")
        withTimeout(10_000) { handleJob.join() }
        assertTrue(handleJob.isCompleted, "the live idle WS was torn down at the Op-Session-TTL (handle() returned)")
        assertTrue(
            elapsedMs >= ttlMs,
            "teardown waited the full TTL (${elapsedMs} ms ≥ ${ttlMs} ms) — a hard, activity-independent cutoff, not an early failure",
        )
        assertTrue(
            elapsedMs < 5_000,
            "teardown fired at the TTL (${elapsedMs} ms), not the 10 s safety window — a real cutoff, not a hang masked by timeout",
        )
        tunnel.close()
    }
}
