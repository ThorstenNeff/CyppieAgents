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
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
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
        override val handshakeHash: ByteArray get() = ByteArray(32)
        override suspend fun receive(): ByteArray? = inbound.receiveCatching().getOrNull()
        override suspend fun send(plaintext: ByteArray) { outbound.trySend(plaintext) }
        override suspend fun close() { inbound.close(); outbound.close() }
        fun deliver(bytes: ByteArray) { inbound.trySend(bytes) }
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
}
