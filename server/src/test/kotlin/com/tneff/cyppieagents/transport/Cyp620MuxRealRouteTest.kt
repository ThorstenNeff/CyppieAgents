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
import com.tneff.cyppieagents.mux.YamuxFlags
import com.tneff.cyppieagents.mux.YamuxFrame
import com.tneff.cyppieagents.mux.YamuxFrameCodec
import com.tneff.cyppieagents.mux.YamuxFrameDecoder
import com.tneff.cyppieagents.mux.YamuxType
import com.tneff.cyppieagents.routing.installPlatform
import com.tneff.cyppieagents.transport.mux.MuxBridge
import com.tneff.cyppieagents.transport.mux.MuxHello
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-620 Increment 3 (step 5c) — the **real-route integration canon** for the mux: drive real HTTP/WS through a
 * [MuxBridge] over a fake [ServerNoiseTunnel] to the ACTUAL [installPlatform] routes on a `127.0.0.1` Netty listener
 * (the CYP-459-T2 harness, now over the mux). This proves what only integration can — that the live datapath holds
 * bilaterally: the routes are UNCHANGED, each stream re-auths (M2), and interleaved streams over ONE tunnel do NOT
 * bleed into each other's real responses.
 *
 * The frame codec / window backpressure / scheduler fairness / stream FIN-RST are unit-teethed in steps 1–4
 * (`YamuxFrameCodecTest`, `YamuxWindowTest`, `MuxWriteSchedulerTest`, `YamuxStreamTest`, `YamuxSessionTest`); this
 * file adds the real-route teeth: served-positive, per-stream auth (M2), NO-BYTE-BLEED over real routes, the G7
 * mixed-mode refusal, and the WS-upgrade surface.
 */
class Cyp620MuxRealRouteTest {

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

    /** A controllable fake L2 tunnel: [deliver] client bytes to the bridge, capture the hub's outbound bytes. */
    private class ControllableTunnel : ServerNoiseTunnel {
        private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
        val outbound = Channel<ByteArray>(Channel.UNLIMITED)
        override val handshakeHash: ByteArray get() = ByteArray(32)
        override suspend fun receive(): ByteArray? = inbound.receiveCatching().getOrNull()
        override suspend fun send(plaintext: ByteArray) { outbound.trySend(plaintext) }
        override suspend fun close() { inbound.close(); outbound.close() }
        fun deliver(bytes: ByteArray) { inbound.trySend(bytes) }
    }

    /** Boot the REAL platform on a loopback Netty listener; return (port, a valid agent bearer). */
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
            WorktreeManager(FakeGit(), Files.createTempDirectory("cyp620-rt").toFile()),
            FakeSpawner(),
            scope,
        ).boot()
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { installPlatform(booted) }.start(wait = false)
        cleanups += { server.stop(0, 0) }
        val port = runBlocking { server.engine.resolvedConnectors() }.first().port
        return port to agentToken
    }

    // ── mux drivers ─────────────────────────────────────────────────────────────────────────────────────────────

    private fun httpGet(path: String, headers: List<String>): String =
        (listOf("GET $path HTTP/1.1", "Host: 127.0.0.1", "Connection: close") + headers).joinToString("\r\n") + "\r\n\r\n"
    private fun statusLine(resp: String) = resp.lineSequence().firstOrNull()?.trim().orEmpty()

    private fun synFrame(streamId: Long, streamClass: Int, request: String) =
        YamuxFrameCodec.encode(YamuxFrame.data(streamId, byteArrayOf(streamClass.toByte()) + request.encodeToByteArray(), YamuxFlags.SYN))

    /**
     * Open [requests] (streamId → request string) as concurrent streams over ONE mux tunnel and collect each stream's
     * HTTP response (its DATA payload, until its FIN). The G7 hello is exchanged first. Returns streamId → response.
     */
    private fun driveMuxStreams(port: Int, requests: Map<Long, String>, streamClass: Int = 3): Map<Long, String> = runBlocking {
        withTimeout(30_000) {
            val tunnel = ControllableTunnel()
            val job = launch(Dispatchers.IO) { MuxBridge(port).bridge(tunnel) }
            tunnel.deliver(MuxHello.ENCODED) // client hello
            requests.forEach { (id, req) -> tunnel.deliver(synFrame(id, streamClass, req)) }

            val decoder = YamuxFrameDecoder()
            val bytes = requests.keys.associateWith { ArrayList<Byte>() }
            val finished = HashSet<Long>()
            var helloSeen = false
            while (finished.size < requests.size) {
                val chunk = tunnel.outbound.receiveCatching().getOrNull() ?: break
                if (!helloSeen) { // the server's hello is the first outbound message (not a yamux frame)
                    helloSeen = true
                    assertTrue(chunk.contentEquals(MuxHello.ENCODED), "the server answers the G7 hello first")
                    continue
                }
                for (f in decoder.feed(chunk)) {
                    val buf = bytes[f.streamId] ?: continue
                    if (f.type == YamuxType.DATA && f.payload.isNotEmpty()) f.payload.forEach { buf.add(it) }
                    if (f.isFin || f.isRst) finished += f.streamId
                }
            }
            tunnel.close(); job.cancel()
            bytes.mapValues { it.value.toByteArray().decodeToString() }
        }
    }

    private fun driveHttpMux(port: Int, request: String): String = driveMuxStreams(port, mapOf(1L to request)).getValue(1L)

    // ── teeth ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun tooth1_realBearer_servedByRealRoute_throughMux_200() {
        val (port, token) = startRealPlatform()
        val resp = driveHttpMux(port, httpGet("/api/agents", listOf("Authorization: Bearer $token")))
        assertTrue("200" in statusLine(resp), "a real agent bearer, wrapped in a yamux stream over the mux, is served 200 by the REAL route: '${statusLine(resp)}'")
    }

    @Test
    fun tooth2to4_perStreamAuth_M2_forgedAndBare401_realBearer200() {
        val (port, token) = startRealPlatform()
        // family I over the mux: a forged already-auth marker grants ZERO — the REAL route 401s exactly as on the wire.
        val forged = driveHttpMux(port, httpGet("/api/agents", listOf("X-Already-Authenticated: true", "X-Local-Request: true")))
        assertTrue("401" in statusLine(forged), "a forged already-auth marker over a mux stream is 401 (M2: the mux grants no ambient trust): '${statusLine(forged)}'")
        // differential: bare, no credential → the SAME 401 (no oracle).
        val bare = driveHttpMux(port, httpGet("/api/agents", emptyList()))
        assertTrue("401" in statusLine(bare), "a bare no-credential mux stream is 401 too — identical reject: '${statusLine(bare)}'")
        // non-vacuous: a real bearer over the SAME mux path IS served.
        val authed = driveHttpMux(port, httpGet("/api/agents", listOf("Authorization: Bearer $token")))
        assertTrue("200" in statusLine(authed), "a real bearer over the same mux path is 200 (non-vacuous)")
    }

    @Test
    fun tooth5to6_noByteBleed_concurrentStreams_overOneTunnel_eachGetsItsOwnRealResponse() {
        val (port, token) = startRealPlatform()
        // THE load-bearing integration proof: an authed stream and a bare stream, INTERLEAVED over ONE tunnel. Each
        // stream's socket must receive EXACTLY its own real-route response — the authed one 200, the bare one 401 —
        // with no cross-stream byte landing in the wrong response (that would be one operator's bytes in another's).
        val out = driveMuxStreams(
            port,
            mapOf(
                1L to httpGet("/api/agents", listOf("Authorization: Bearer $token")), // authed → 200
                3L to httpGet("/api/agents", emptyList()),                             // bare  → 401
                5L to httpGet("/api/health", emptyList()),                             // health → 200 (open)
            ),
        )
        assertTrue("200" in statusLine(out.getValue(1L)), "stream 1 (authed) got ITS 200: '${statusLine(out.getValue(1L))}'")
        assertTrue("401" in statusLine(out.getValue(3L)), "stream 3 (bare) got ITS 401 — the 200 did NOT bleed in: '${statusLine(out.getValue(3L))}'")
        assertTrue("200" in statusLine(out.getValue(5L)), "stream 5 (health) got ITS 200: '${statusLine(out.getValue(5L))}'")
        // And no response is a mix: the bare stream's body must not contain the authed agents payload.
        assertFalse(out.getValue(3L).contains("\"role\""), "stream 3's 401 body carries none of stream 1's agents JSON (no byte-bleed)")
    }

    @Test
    fun tooth7_g7_poolPeerNoHello_refused_noRouteReached() {
        val (port, _) = startRealPlatform()
        // A pool-mode / mis-flagged peer sends HTTP bytes as the FIRST message (no G7 hello) → MuxBridge refuses
        // fail-closed: the tunnel closes, no session, no route reached. Differential vs the hello path (tooth 1).
        val out = runBlocking {
            withTimeout(15_000) {
                val tunnel = ControllableTunnel()
                val job = launch(Dispatchers.IO) { MuxBridge(port).bridge(tunnel) }
                tunnel.deliver(httpGet("/api/health", emptyList()).encodeToByteArray()) // HTTP, not a hello
                val sb = StringBuilder()
                withTimeoutOrNull(3_000) {
                    while (true) {
                        val chunk = tunnel.outbound.receiveCatching().getOrNull() ?: break
                        sb.append(chunk.decodeToString())
                    }
                }
                tunnel.close(); job.cancel()
                sb.toString()
            }
        }
        assertTrue(out.isEmpty(), "a peer with no G7 hello is refused fail-closed — no bytes bridged, no route reached: '$out'")
    }

    @Test
    fun tooth8to9_wsUpgradeSurface_throughMux_realTokenServed_forgedClosed1008() {
        val (port, token) = startRealPlatform()
        val forged = driveWsMux(port, wsUpgrade("/ws/agent?agentId=backend", listOf("X-Already-Authenticated: true")))
        assertTrue(switchedProtocols(forged), "the WS handshake reaches 101 over the mux (auth runs post-upgrade)")
        assertTrue(containsClose1008(forged), "a forged no-token WS-upgrade over the mux is rejected: close(1008)")
        val authed = driveWsMux(port, wsUpgrade("/ws/agent?agentId=backend", listOf("Authorization: Bearer $token")))
        assertTrue(switchedProtocols(authed), "the authed WS handshake reaches 101 over the mux")
        assertFalse(containsClose1008(authed), "a real agent token over the mux WS surface is NOT closed 1008 (auth accepted)")
    }

    // ── WS helpers ──────────────────────────────────────────────────────────────────────────────────────────────

    private fun wsUpgrade(path: String, headers: List<String>): String =
        (listOf(
            "GET $path HTTP/1.1", "Host: 127.0.0.1", "Upgrade: websocket", "Connection: Upgrade",
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==", "Sec-WebSocket-Version: 13",
        ) + headers).joinToString("\r\n") + "\r\n\r\n"

    /** Drive one WS-upgrade through the mux (stream 1); collect the stream's DATA bytes for [windowMs] (the WS stays
     *  open on success, so this is time-windowed, not FIN-terminated), then tear down. */
    private fun driveWsMux(port: Int, request: String, windowMs: Long = 3_000): ByteArray = runBlocking {
        val tunnel = ControllableTunnel()
        val job = launch(Dispatchers.IO) { MuxBridge(port).bridge(tunnel) }
        tunnel.deliver(MuxHello.ENCODED)
        tunnel.deliver(synFrame(1L, streamClass = 1, request = request))
        val decoder = YamuxFrameDecoder()
        val buf = ArrayList<Byte>()
        var helloSeen = false
        withTimeoutOrNull(windowMs) {
            while (true) {
                val chunk = tunnel.outbound.receiveCatching().getOrNull() ?: break
                if (!helloSeen) { helloSeen = true; continue }
                for (f in decoder.feed(chunk)) if (f.streamId == 1L && f.type == YamuxType.DATA) f.payload.forEach { buf.add(it) }
            }
        }
        tunnel.close(); job.cancel()
        buf.toByteArray()
    }

    private fun containsClose1008(bytes: ByteArray): Boolean {
        for (i in 0..bytes.size - 4) {
            if (bytes[i] == 0x88.toByte() && bytes[i + 2] == 0x03.toByte() && bytes[i + 3] == 0xF0.toByte()) return true
        }
        return false
    }
    private fun switchedProtocols(bytes: ByteArray) = "101" in bytes.decodeToString().lineSequence().firstOrNull().orEmpty()
}
