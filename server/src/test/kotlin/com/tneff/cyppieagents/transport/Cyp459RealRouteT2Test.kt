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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-459 (S3) — **T2 against the REAL route surface** (not CYP-458's synthetic probe): the RR3 bridge presents the
 * tunnel's bytes to the ACTUAL [installPlatform] routes over a real `127.0.0.1` Netty listener. A forged
 * "already-authenticated" marker over the loopback bridge is rejected by the real auth guard exactly as a network
 * request (**family I — frame/header forgery**), the marker grants ZERO (the **differential** zero-privilege claim:
 * marker vs no-marker → the same 401, no oracle), and a real bearer over the SAME loopback IS served (non-vacuous).
 *
 * (The exhaustive 10-family live run — incl. the WS-upgrade `/ws` surface, family G — is the Tester's independent
 * plan submitted into the Reviewer gate; this is the backend's own faithful real-route proof of the invariant.)
 */
class Cyp459RealRouteT2Test {

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

    /** A controllable fake L2 tunnel: deliver client bytes to the bridge, capture the hub's response bytes. */
    private class ControllableTunnel : ServerNoiseTunnel {
        private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
        val outbound = Channel<ByteArray>(Channel.UNLIMITED)
        @Volatile private var faultOnDrain = false
        override val handshakeHash: ByteArray get() = ByteArray(32)
        override suspend fun receive(): ByteArray? {
            val r = inbound.receiveCatching()
            if (r.isClosed && faultOnDrain) throw RuntimeException("AEAD decrypt-fail / tamper (abrupt L2 fault)")
            return r.getOrNull()
        }
        override suspend fun send(plaintext: ByteArray) { outbound.trySend(plaintext) }
        override suspend fun close() { inbound.close(); outbound.close() }
        fun deliver(bytes: ByteArray) { inbound.trySend(bytes) }
        /** CYP-609: the UNTRUSTED relay/L2 closing CLEANLY — `receive()` then returns null → the bridge graceful-FINs. */
        fun dropRelay() { inbound.close() }
        /** CYP-609: the UNTRUSTED relay/L2 faulting ABRUPTLY — `receive()` then THROWS → the bridge RSTs (mutation anchor). */
        fun throwRelay() { faultOnDrain = true; inbound.close() }
    }

    /** Boot the REAL platform on a loopback Netty listener; return (port, a valid agent bearer token). */
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
            WorktreeManager(FakeGit(), Files.createTempDirectory("cyp459-rt").toFile()),
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

    @Test
    fun t2_forgedAuthMarkers_overLoopbackBridge_rejectedByRealRoutes() {
        val (port, token) = startRealPlatform()

        // family I (frame/header forgery): no credential + forged "already-auth" markers → the REAL route 401s.
        val forged = driveHttp(
            port,
            httpGet("/api/agents", listOf("X-Already-Authenticated: true", "X-Forwarded-For: 127.0.0.1", "X-Local-Request: true")),
        )
        assertTrue("401" in statusLine(forged), "forged already-auth markers over the loopback bridge are rejected by the REAL route: '${statusLine(forged)}'")

        // ★ differential zero-privilege: NO markers, no credential → the SAME 401 (the marker leaks nothing / grants nothing).
        val bare = driveHttp(port, httpGet("/api/agents", emptyList()))
        assertTrue("401" in statusLine(bare), "a bare no-credential request is 401 too — identical reject, no oracle: '${statusLine(bare)}'")

        // positive (non-vacuous): a REAL bearer over the SAME loopback path IS served by the real route.
        val authed = driveHttp(port, httpGet("/api/agents", listOf("Authorization: Bearer $token")))
        assertTrue("200" in statusLine(authed), "a real agent bearer over the SAME loopback is served: '${statusLine(authed)}'")
    }

    // ---- family G: the WS-upgrade surface (/ws/agent) ----

    /** Drive one raw WS-upgrade through the bridge; collect the response bytes within [windowMs] (or until the server
     *  closes), then tear down. An unauthorized upgrade → 101 then a `close(VIOLATED_POLICY=1008)` frame. */
    private fun driveWs(port: Int, request: String, windowMs: Long = 3_000): ByteArray = runBlocking {
        val tunnel = ControllableTunnel()
        val job = launch(Dispatchers.IO) { LoopbackBridge(port).bridge(tunnel) }
        tunnel.deliver(request.encodeToByteArray())
        val buf = ArrayList<Byte>()
        withTimeoutOrNull(windowMs) {
            while (true) {
                val chunk = tunnel.outbound.receiveCatching().getOrNull() ?: break // server closed → bridge done
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

    /** A server-sent WS `close` frame carrying code 1008 (VIOLATED_POLICY): opcode 0x88, then the 2-byte code 0x03F0. */
    private fun containsClose1008(bytes: ByteArray): Boolean {
        for (i in 0..bytes.size - 4) {
            if (bytes[i] == 0x88.toByte() && bytes[i + 2] == 0x03.toByte() && bytes[i + 3] == 0xF0.toByte()) return true
        }
        return false
    }
    private fun switchedProtocols(bytes: ByteArray) = "101" in bytes.decodeToString().lineSequence().firstOrNull().orEmpty()

    @Test
    fun t2_familyG_wsUpgrade_forgedSession_rejectedByRealWsAuth() {
        val (port, token) = startRealPlatform()

        // family G (session/token forgery over the WS-upgrade): a forged already-auth marker, NO token → the REAL
        // WS authorizer (tokenAuthorize) rejects post-handshake with close(1008). The upgrade reaches 101 (so this is
        // the AUTH layer rejecting, not the origin guard) then closes.
        val forged = driveWs(port, wsUpgrade("/ws/agent?agentId=backend", listOf("X-Already-Authenticated: true", "X-Local-Request: true")))
        assertTrue(switchedProtocols(forged), "the WS handshake reaches 101 (auth runs post-upgrade)")
        assertTrue(containsClose1008(forged), "a forged already-auth WS-upgrade with no token is rejected: close(1008 VIOLATED_POLICY)")

        // ★ differential: NO markers, no token → the SAME close(1008) — the marker grants nothing on the WS surface.
        val bare = driveWs(port, wsUpgrade("/ws/agent?agentId=backend", emptyList()))
        assertTrue(containsClose1008(bare), "a bare no-token WS-upgrade is closed 1008 too — identical reject, no oracle")

        // positive (non-vacuous): a REAL agent token over the SAME WS surface is NOT rejected with 1008 (auth passed).
        val authed = driveWs(port, wsUpgrade("/ws/agent?agentId=backend", listOf("Authorization: Bearer $token")))
        assertTrue(switchedProtocols(authed), "the authed WS handshake reaches 101")
        assertTrue(!containsClose1008(authed), "a real agent token over the SAME WS surface is NOT closed 1008 (auth accepted)")
    }

    // ---- CYP-609: what the graceful FIN does NOT regress — the real guard is Ktor's CONTENT parser (completeness) ----
    //
    // CORRECTED (Assist re-verified): the graceful FIN aborts the POST *response* either way (even for a complete body),
    // so a response-STATUS check measures the teardown, NOT a rejection — it is vacuous. The ONLY sound signal is the
    // COMMIT: drive the POST, then a FOLLOW-UP GET of the channel — is the message present? The guard that actually
    // fires is Ktor's content parser (a JSON object must be COMPLETE), NOT the transport length or the FIN/RST teardown.

    /** Build a POST to `po-backend` with an explicit [contentLength] and a raw [body] (which embeds the marker). */
    private fun clPost(token: String, contentLength: Int, body: String): String =
        "POST /api/channels/po-backend/messages HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
            "Authorization: Bearer $token\r\nContent-Type: application/json\r\nContent-Length: $contentLength\r\n\r\n$body"

    private fun hexLen(body: String): String = body.encodeToByteArray().size.toString(16)
    /** A COMPLETE chunked stream: one chunk of [body] + the terminal 0-chunk. */
    private fun completeChunkStream(body: String): String = "${hexLen(body)}\r\n$body\r\n0\r\n\r\n"

    /** Drive [request] (its body embeds [marker]) through the bridge, end the upstream with [fault] (dropRelay = clean
     *  FIN, throwRelay = abrupt RST), then GET the channel over a FRESH connection and return whether [marker] was
     *  COMMITTED (the message is present). The COMMIT — not the (always-aborted) POST response — is the sound signal. */
    private fun committedAfterFault(port: Int, token: String, request: String, marker: String, fault: (ControllableTunnel) -> Unit): Boolean {
        runBlocking {
            withTimeout(30_000) {
                val tunnel = ControllableTunnel()
                val job = launch(Dispatchers.IO) { LoopbackBridge(port).bridge(tunnel) }
                tunnel.deliver(request.encodeToByteArray())
                fault(tunnel)
                job.join()
            }
        }
        val messages = driveHttp(port, httpGet("/api/channels/po-backend/messages", listOf("Authorization: Bearer $token")))
        return messages.contains(marker)
    }

    /** Drive a COMPLETE POST NORMALLY (Connection: close, NO fault) so the handler reads the full body, commits, and
     *  responds before a clean close, then GET the channel and return whether [marker] committed. DETERMINISTIC — no
     *  teardown race (unlike [committedAfterFault], where the fault can interrupt the commit even of a complete body). */
    private fun committedViaNormalPost(port: Int, token: String, request: String, marker: String): Boolean {
        driveHttp(port, request)
        val messages = driveHttp(port, httpGet("/api/channels/po-backend/messages", listOf("Authorization: Bearer $token")))
        return messages.contains(marker)
    }

    private fun completePostClose(token: String, body: String): String =
        "POST /api/channels/po-backend/messages HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $token\r\n" +
            "Content-Type: application/json\r\nConnection: close\r\nContent-Length: ${body.encodeToByteArray().size}\r\n\r\n$body"

    private fun completeChunkedPostClose(token: String, body: String): String =
        "POST /api/channels/po-backend/messages HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $token\r\n" +
            "Content-Type: application/json\r\nConnection: close\r\nTransfer-Encoding: chunked\r\n\r\n${completeChunkStream(body)}"

    @Test
    fun cyp609_c3_genuineGuard_incompleteBodyNotCommitted_completeCommitted_finEqualsRst() {
        val (port, token) = startRealPlatform()
        // The REAL guard is Ktor's CONTENT parser (a JSON object must be COMPLETE), not the transport length or the
        // FIN/RST teardown — and this is the DETERMINISTIC part (an incomplete object has nothing to commit, so no
        // handler-commit-vs-teardown race). An incomplete/mid-object body ({"body":"X  — no closing "}) truncated by the
        // graceful FIN → the parser rejects → NEVER committed. MUTATION ANCHOR / FIN==RST no-regression: the SAME
        // incomplete body truncated by an RST is ALSO never committed → the guard is the PARSER, not the teardown.
        // The POSITIVE CONTROL uses the NORMAL route (Connection: close, no fault) — a complete POST commits
        // deterministically (proving the route CAN commit, so the not-committed above is non-vacuous). It is NOT run
        // over the fault path, because the fault can race-interrupt the commit even of a COMPLETE body (that race was
        // the earlier flake; the racy complete-object-with-truncated-framing case is characterized below, not asserted).
        val incomplete = { m: String -> clPost(token, contentLength = 100, body = "{\"body\":\"$m") } // no closing "}

        assertFalse(
            committedAfterFault(port, token, incomplete("g1incFin"), "g1incFin") { it.dropRelay() },
            "an incomplete/mid-object body truncated by the graceful FIN is NEVER committed — Ktor's content parser rejects it (the REAL, deterministic guard)",
        )
        assertFalse(
            committedAfterFault(port, token, incomplete("g1incRst"), "g1incRst") { it.throwRelay() },
            "MUTATION ANCHOR: the same incomplete body under an RST is ALSO never committed → the guard is the PARSER, not the FIN/RST teardown",
        )
        assertTrue(
            committedViaNormalPost(port, token, completePostClose(token, "{\"body\":\"g1cmp\"}"), "g1cmp"),
            "POSITIVE CONTROL (normal route, deterministic): a COMPLETE POST IS committed → the not-committed above is non-vacuous",
        )
    }

    /**
     * CYP-609 chunked — DETERMINISTIC positive control + honest characterization. A COMPLETE chunked body (chunk +
     * terminal `0\r\n\r\n`) over the graceful FIN IS committed. The TRUNCATED-chunked case (a chunk whose CONTENT is a
     * complete, parseable JSON object but with NO terminal 0-chunk) is **env-timing RACY**: the handler can commit the
     * parsed object BEFORE the chunked decoder's missing-terminal EOF-reject fires — observed committing in one env and
     * rejecting in mine/Assist's (empirical rate in the CYP-611/615 thread). So it is **NOT asserted** here — the first
     * draft's `assertFalse(truncated-chunked committed)` was a FLAKY, FALSE "chunked rejects truncation" claim. It is
     * the SAME content-completeness-vs-transport-framing gap as the Content-Length case → **CYP-615** now covers BOTH
     * Content-Length and chunked. The deterministic guard (an INCOMPLETE JSON object is NEVER committed, transport-
     * agnostic) is pinned by [cyp609_c3_genuineGuard_incompleteBodyNotCommitted_completeCommitted_finEqualsRst].
     */
    @Test
    fun cyp609_c3_chunked_completeCommitted_truncatedIsRacy_CYP615() {
        val (port, token) = startRealPlatform()
        assertTrue(
            committedViaNormalPost(port, token, completeChunkedPostClose(token, "{\"body\":\"c2cmp\"}"), "c2cmp"),
            "DETERMINISTIC positive control: a COMPLETE chunked body (with terminal 0-chunk) via the normal route IS committed",
        )
    }

    // ── CYP-609 CHARACTERIZATION (prose, NOT asserted — the outcome is RACY) ─────────────────────────────────────────
    // The one narrow reality that made the first draft's claim wrong, kept as honest documentation so the false
    // statement ("Ktor rejects a truncated Content-Length / chunked body") can never silently return: when a truncated
    // transport frame (a Content-Length OVERSTATED, or a chunked body with NO terminal 0-chunk) carries bytes that
    // already form a COMPLETE, parseable JSON object, the handler USUALLY commits the parsed object BEFORE the transport
    // layer's truncation-reject fires — a handler-commit-vs-transport-reject RACE. Empirically (this env, 10 samples
    // each): CL-overstated committed ~9/10, chunked-no-terminal ~7-9/10 — and another env committed where mine/Assist's
    // rejected. So the outcome is NON-DETERMINISTIC and is deliberately NOT asserted: a single-run
    // `assertTrue/False(committed)` on this middle case would be FLAKY (exactly the flake the merged-tree gate caught).
    // Only DETERMINISTIC facts are asserted — the genuine guard (an INCOMPLETE JSON object is NEVER committed, pinned by
    // [cyp609_c3_genuineGuard_...]) and the positive controls (a COMPLETE body over the FIN IS committed). The route-wide
    // fix (enforce the declared frame length / require the terminal chunk BEFORE the body-parse) is a SEPARATE concern →
    // **CYP-615**, which covers BOTH Content-Length and chunked (the same content-completeness-vs-framing gap).
}
