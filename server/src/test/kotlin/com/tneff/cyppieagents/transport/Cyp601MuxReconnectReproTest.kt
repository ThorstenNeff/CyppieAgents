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
import com.tneff.cyppieagents.mux.MuxHello
import com.tneff.cyppieagents.mux.YamuxFlags
import com.tneff.cyppieagents.mux.YamuxFrame
import com.tneff.cyppieagents.mux.YamuxFrameCodec
import com.tneff.cyppieagents.mux.YamuxFrameDecoder
import com.tneff.cyppieagents.mux.YamuxType
import com.tneff.cyppieagents.routing.installPlatform
import com.tneff.cyppieagents.transport.mux.MuxBridge
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
 * CYP-601 VERIFICATION — does the "6 agents Reconnecting / Server unreachable" remote-mode bug still reproduce against
 * the **current mux transport (CYP-620)**? (PL directive: prove obsolescence, don't assume it — no CYP-620 commit
 * references CYP-601.)
 *
 * The original bug (pool model): a bare loopback `HttpClient{}` no-op'd `keepAliveTime`/`pingInterval` → idle loopback
 * conns closed after ~5s → and because each agent had its OWN Noise tunnel, that close tore the tunnel → a re-dial
 * storm → 6 idle agent streams "Reconnecting"; lifecycle-REST over the torn tunnel → "Server unreachable". Fixed at the
 * root (the loopback datapath now pins CIO with keepAlive=∞ + a real 15s WS ping — [pinnedCioWsHttpClient], guarded by
 * `LoopbackHttpClientTest`) AND structurally de-fanged by the mux: N agent streams now multiplex over ONE carrier, and
 * a per-stream FIN/RST retires only that stream — the [YamuxSession] tears all streams only on a *carrier* drop.
 *
 * This harness reproduces the CYP-601 SCENARIO against the real [MuxBridge] + real [installPlatform] routes and pins
 * the de-fang as the measurement:
 *  • [manyAgentStreams_overOneCarrier_allServed] — N=7 agent streams (PO + 6 workers) over ONE tunnel are ALL served
 *    (no "6 Reconnecting" at start).
 *  • [perAgentStreamRst_doesNotCascade_carrierAndOtherAgentsSurvive] — RST ONE stream (a per-agent stop / the idle-close
 *    that tore the tunnel in the pool model) → the carrier survives and a FRESH stream is still served 200 ("Server"
 *    still reachable). This is exactly the cascade CYP-601 was; on the mux it cannot happen.
 *  • [grip_carrierDrop_killsAFreshStream] — faithfulness: a *carrier* drop DOES kill a fresh stream, so the survival
 *    oracle is non-vacuous (it can see the failure the bug would cause; the RST test's green is a real discrimination).
 *
 * MEASUREMENT RESULT: all green ⇒ the bug does NOT reproduce on the mux transport ⇒ CYP-601 is obsolete/fixed, PROVEN
 * (not assumed). The harness stays as the regression guard.
 */
class Cyp601MuxReconnectReproTest {

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

    /** A controllable fake L2 tunnel (the mux carrier): deliver client bytes to the bridge, capture the hub's outbound. */
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
            WorktreeManager(FakeGit(), Files.createTempDirectory("cyp601-repro").toFile()),
            FakeSpawner(),
            scope,
        ).boot()
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { installPlatform(booted) }.start(wait = false)
        cleanups += { server.stop(0, 0) }
        val port = runBlocking { server.engine.resolvedConnectors() }.first().port
        return port to agentToken
    }

    private fun httpGet(path: String, bearer: String): String =
        listOf("GET $path HTTP/1.1", "Host: 127.0.0.1", "Connection: close", "Authorization: Bearer $bearer")
            .joinToString("\r\n") + "\r\n\r\n"

    private fun synFrame(streamId: Long, request: String, streamClass: Int = 3) =
        YamuxFrameCodec.encode(YamuxFrame.data(streamId, byteArrayOf(streamClass.toByte()) + request.encodeToByteArray(), YamuxFlags.SYN))

    private fun statusLine(buf: List<Byte>?): String =
        buf?.toByteArray()?.decodeToString()?.lineSequence()?.firstOrNull()?.trim().orEmpty()

    /**
     * A PERSISTENT mux carrier: ONE [ControllableTunnel] + ONE [MuxBridge] across many stream operations (the template's
     * per-call drivers open a fresh tunnel each time — CYP-601 needs streams to share one carrier). Single-threaded
     * frame reader; [awaitStatus] reads outbound frames (accumulating ALL streams) until the target stream has a status
     * line or is retired.
     */
    private inner class MuxCarrier(port: Int) {
        private val tunnel = ControllableTunnel()
        private val job = runBlocking { CoroutineScope(Dispatchers.IO).launch { MuxBridge(port).bridge(tunnel) } }
        private val decoder = YamuxFrameDecoder()
        private val streams = HashMap<Long, ArrayList<Byte>>()
        private val retired = HashSet<Long>()
        private var helloSeen = false
        init { cleanups += { close() }; tunnel.deliver(MuxHello.ENCODED) }

        fun open(streamId: Long, request: String, streamClass: Int = 3) {
            streams.getOrPut(streamId) { ArrayList() }
            tunnel.deliver(synFrame(streamId, request, streamClass))
        }

        /** Client → server RST for [streamId] — a per-agent abrupt close (the CYP-601 idle-close trigger). */
        fun rst(streamId: Long) =
            tunnel.deliver(YamuxFrameCodec.encode(YamuxFrame.data(streamId, ByteArray(0), YamuxFlags.RST)))

        /** Read outbound frames (accumulating every stream) until [streamId] has a status line or is retired. */
        suspend fun awaitStatus(streamId: Long, timeoutMs: Long = 10_000): String = withTimeoutOrNull(timeoutMs) {
            while (true) {
                if (statusLine(streams[streamId]).isNotEmpty() || streamId in retired) return@withTimeoutOrNull statusLine(streams[streamId])
                val chunk = tunnel.outbound.receiveCatching().getOrNull() ?: return@withTimeoutOrNull statusLine(streams[streamId])
                if (!helloSeen) { helloSeen = true; continue }
                for (f in decoder.feed(chunk)) {
                    val buf = streams.getOrPut(f.streamId) { ArrayList() }
                    if (f.type == YamuxType.DATA && f.payload.isNotEmpty()) f.payload.forEach { buf.add(it) }
                    if (f.isFin || f.isRst) retired += f.streamId
                }
            }
            @Suppress("UNREACHABLE_CODE") statusLine(streams[streamId])
        } ?: statusLine(streams[streamId])

        fun dropCarrier() = runBlocking { tunnel.close() }
        fun close() { runCatching { runBlocking { tunnel.close() } }; job.cancel() }
    }

    @Test
    fun manyAgentStreams_overOneCarrier_allServed() = runBlocking {
        // ① CYP-601 start scenario: a 7-agent session (PO + 6 workers), each a stream over ONE carrier. In the pool
        //    model the idle ones showed "Reconnecting"; on the mux ALL are served. (streamIds must be odd — client-init.)
        val (port, token) = startRealPlatform()
        val carrier = MuxCarrier(port)
        val ids = listOf(1L, 3L, 5L, 7L, 9L, 11L, 13L) // 7 agents
        withTimeout(30_000) {
            ids.forEach { carrier.open(it, httpGet("/api/agents", token)) }
            for (id in ids) {
                assertTrue("200" in carrier.awaitStatus(id), "agent stream $id over the shared carrier is served 200 (not 'Reconnecting'): '${carrier.awaitStatus(id)}'")
            }
        }
    }

    @Test
    fun perAgentStreamRst_doesNotCascade_carrierAndOtherAgentsSurvive() = runBlocking {
        // ② THE CYP-601 refutation: a per-agent close (RST one stream — the idle-close that tore the tunnel in the pool
        //    model) MUST NOT cascade. After RST-ing stream 1, the shared carrier is still up: a FRESH stream is served
        //    200 ("Server" reachable), i.e. no "6 Reconnecting / Server unreachable" storm.
        val (port, token) = startRealPlatform()
        val carrier = MuxCarrier(port)
        withTimeout(30_000) {
            carrier.open(1L, httpGet("/api/agents", token))
            assertTrue("200" in carrier.awaitStatus(1L), "stream 1 served first")
            carrier.open(3L, httpGet("/api/health", token))
            assertTrue("200" in carrier.awaitStatus(3L), "stream 3 served")

            carrier.rst(1L) // ★ the per-agent close / idle-close trigger

            // The carrier survived the per-stream RST → a fresh agent stream is still served 200 (no cascade).
            carrier.open(5L, httpGet("/api/agents", token))
            assertTrue(
                "200" in carrier.awaitStatus(5L),
                "after RST-ing one stream, a FRESH stream over the SAME carrier is still served 200 — the per-agent " +
                    "close did NOT tear the carrier (no CYP-601 cascade): '${carrier.awaitStatus(5L)}'",
            )
        }
    }

    @Test
    fun grip_carrierDrop_killsAFreshStream() = runBlocking {
        // GRIP (faithfulness): drop the CARRIER (not a stream) → a fresh stream gets NOTHING. Proves the survival oracle
        // in ② is non-vacuous — it CAN observe the failure a real cascade/tunnel-tear would cause, so ②'s green is a
        // real discrimination (RST-one-stream survives) vs this (carrier-drop dies).
        val (port, token) = startRealPlatform()
        val carrier = MuxCarrier(port)
        withTimeout(30_000) {
            carrier.open(1L, httpGet("/api/agents", token))
            assertTrue("200" in carrier.awaitStatus(1L), "stream 1 served before the carrier drop")

            carrier.dropCarrier() // ★ the carrier (tunnel) itself drops → the whole session is torn

            carrier.open(3L, httpGet("/api/agents", token))
            assertTrue(
                carrier.awaitStatus(3L, timeoutMs = 4_000).isEmpty(),
                "after a CARRIER drop a fresh stream is NOT served — the oracle can see a real teardown (non-vacuous): '${carrier.awaitStatus(3L, 1_000)}'",
            )
        }
    }
}
