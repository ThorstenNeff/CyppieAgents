package com.tneff.cyppieagents.gateway

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.FakeIdentityProvider
import com.tneff.cyppieagents.auth.InMemoryRoleStore
import com.tneff.cyppieagents.auth.ParticipantTokenStore
import com.tneff.cyppieagents.auth.ResolvedIdentity
import com.tneff.cyppieagents.model.TerminalClientFrame
import com.tneff.cyppieagents.model.TerminalInput
import com.tneff.cyppieagents.model.TerminalOutput
import com.tneff.cyppieagents.model.TerminalServerFrame
import com.tneff.cyppieagents.pty.PtyManager
import com.tneff.cyppieagents.routing.InMemoryTerminalGrants
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.terminalSocket
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-638 **S4/S5 boundary — the revoke-reconnect security corner**: after a terminal grant is revoked and the live
 * socket is torn (GatewayS5Test ②), can the revoked subject **reconnect with `?since` and keep reading** across the
 * gateway hop? This is the S4 reconnect flavor (`?since` pass-through) meeting the S5 security surface — the corner
 * that falls between "revoke tears the socket" (S5, proven) and "`?since` pass-through" (S4, Backend). Worst-case: the
 * revoke only killed the ONE socket, not the CAPABILITY → a `?since`-reconnect resumes the shell → **the revoke was
 * cosmetic**.
 *
 * **What the code does (pinned here, not cited):** the write-tier gate (`terminalPrincipalOrNull` + `mayOpenTerminal`)
 * runs on **every** connect (TerminalSocket.kt:72-78), BEFORE the attach/spawn — so a reconnect re-evaluates the grant;
 * a revoked delegable subject's `mayOpen` is now default-DENY → **1008 `forbidden`**. And `?since` is **not read** by
 * `terminalSocket` at all (replay is the attach-path scrollback, which is BEHIND the gate) — so `?since` is no bypass.
 * The gateway forwards `?since` verbatim (S4) but it changes nothing: the hub re-gates. ⟹ revoke is **durable**.
 *
 * **Quadrants (CYP-655/S5 discipline), all across the REAL gateway hop:**
 *  • **anchor** ([anchor_grantedMember_reconnectsWithSince_getsWorkingShell]) — a GRANTED member `?since`-reconnects and
 *    gets a working shell (input→PTY). Positive control: `?since`-reconnect IS admitted + functional when authorized, so
 *    the refusals below are due to the REVOKE, not `?since` breaking the handshake.
 *  • **② spawn-path** ([revokedMember_cannotReconnectWithSince_refusedAtEdge]) — grant→live→revoke (socket torn)→
 *    `?since`-reconnect → **1008 `forbidden`**. The revoke is durable; `?since` gives no reconnect bypass. Deterministic.
 *  • **②b attach-corner — the literal "weiterlesen"** ([revokedMember_cannotReattachToLiveMotorSession_afterRevoke]) —
 *    a motor-owned PTY (seeded, still LIVE) that OUTLIVES the torn socket: a granted member attaches + reads the seed
 *    (weiterlesen works when authorized), then revoke tears the viewer (motor lives on), then the `?since`-reconnect is
 *    **refused (1008) BEFORE it can reattach + replay** — even though the live session with readable content is right there.
 *
 * **Mutation (non-vacuity, reported+reverted):** make the grant store forget the revoke (`InMemoryTerminalGrants.mayOpen`
 * → always true) — a store that revokes the SOCKET but not the CAPABILITY. Both "refused" tests then RED (the revoked
 * reconnect is admitted + reads); anchor stays green. Proves the per-connect gate re-check is load-bearing for revoke
 * durability, not incidental.
 *
 * **Where the tooth stops:** it pins revoke-durability across a `?since`-reconnect through the gateway↔hub↔PTY path
 * (spawn + attach). It does NOT re-prove `?since` REPLAY semantics for the event sockets (that is Backend's S4 `?since`
 * tooth at the seam — a different socket class), nor the hub-side gate logic in isolation (MemberTerminalDenyTest).
 */
class GatewayS4RevokeReconnectTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val servers = mutableListOf<EmbeddedServer<*, *>>()
    private val cleanups = mutableListOf<() -> Unit>()
    private var wsClient: HttpClient? = null

    @AfterTest fun tearDown() {
        runCatching { wsClient?.close() }
        cleanups.forEach { runCatching { it() } }
        servers.forEach { runCatching { it.stop(0, 0) } }
        scope.cancel()
    }

    private val FAKE_TUI = """
        #!/usr/bin/env bash
        while IFS= read -r line; do printf 'GOT:%s\n' "${'$'}line"; done
    """.trimIndent()
    private fun fakeTui(): File =
        Files.createTempFile("cyp638s4-tui", ".sh").toFile().apply { writeText(FAKE_TUI); setExecutable(true) }

    private fun registry() = TokenRegistry(mapOf("tok-backend" to "backend"), operatorToken = "tok-op")
    private fun deps(reg: TokenRegistry) = AuthDeps(
        tokens = reg,
        idp = FakeIdentityProvider(mapOf("sess-member" to ResolvedIdentity("id-member", verified = true))),
        roles = InMemoryRoleStore(bootstrapOperatorId = "id-op"),
        nowMs = { 1_000L },
        participantTokens = ParticipantTokenStore { 1_000L },
    )

    private fun newMgr(): PtyManager = PtyManager(
        worktreeDirOf = { Files.createTempDirectory("cyp638s4-wt").toFile() },
        resolveApiKey = { null },
        scope = scope,
        command = listOf(fakeTui().absolutePath),
    )

    /** Mount the production `terminalSocket` on [mgr] + [grants] as a real hub; return its port. */
    private fun startTerminalHub(mgr: PtyManager, grants: InMemoryTerminalGrants): Int {
        val reg = registry()
        val hub = embeddedServer(Netty, port = 0) {
            install(WebSockets)
            routing { terminalSocket({ mgr }, knowsAgent = { it == "backend" }, registry = reg, deps = deps(reg), grants = grants) }
        }.start(wait = false)
        servers += hub
        return runBlocking { hub.engine.resolvedConnectors().first().port }
    }

    private fun startGateway(hubPort: Int): Int {
        val gw = embeddedServer(Netty, port = 0) { gatewayModule("http://127.0.0.1:$hubPort") }.start(wait = false)
        servers += gw
        return runBlocking { gw.engine.resolvedConnectors().first().port }
    }

    private fun client(): HttpClient = HttpClient(CIO) { install(ClientWebSockets) }.also { wsClient = it }

    private fun input(text: String) = Frame.Text(
        CommJson.encodeToString(
            TerminalClientFrame.serializer(),
            TerminalInput(Base64.getEncoder().encodeToString(text.toByteArray())) as TerminalClientFrame,
        ),
    )
    private fun DefaultClientWebSocketSession.decode(f: Frame): TerminalServerFrame =
        CommJson.decodeFromString(TerminalServerFrame.serializer(), (f as Frame.Text).readText())
    private suspend fun DefaultClientWebSocketSession.readUntil(needle: String): Boolean {
        val seen = StringBuilder()
        return withTimeout(15_000) {
            for (frame in incoming) {
                val f = decode(frame)
                if (f is TerminalOutput) {
                    seen.append(String(Base64.getDecoder().decode(f.dataBase64)))
                    if (seen.contains(needle)) return@withTimeout true
                }
            }
            false
        }
    }

    // ---- anchor — a GRANTED member's ?since-reconnect IS admitted + functional (positive control) ----

    @Test
    fun anchor_grantedMember_reconnectsWithSince_getsWorkingShell() = runBlocking {
        val grants = InMemoryTerminalGrants().apply { grant("backend", "id-member") }
        val gp = startGateway(startTerminalHub(newMgr(), grants))
        // A reconnect-flavored connect carrying ?since (as an S4 reconnect would) — the granted member gets a live shell.
        client().webSocket(
            "ws://127.0.0.1:$gp/ws/terminal?agentId=backend&since=0",
            request = { header("X-Session-Token", "sess-member") },
        ) {
            send(input("hi\n"))
            assertTrue(
                readUntil("GOT:hi"),
                "ANCHOR: a GRANTED member's ?since-reconnect is admitted through the gateway and reaches a live PTY — so " +
                    "the refusals below are due to the REVOKE, not ?since breaking the reconnect.",
            )
        }
    }

    // ---- ② spawn-path — a revoked member's ?since-reconnect is refused at the edge ----

    @Test
    fun revokedMember_cannotReconnectWithSince_refusedAtEdge() = runBlocking {
        val grants = InMemoryTerminalGrants().apply { grant("backend", "id-member") }
        val gp = startGateway(startTerminalHub(newMgr(), grants))
        // Connect #1 — the granted member's shell is live through the gateway; then revoke WHILE open (socket #1 torn).
        client().webSocket(
            "ws://127.0.0.1:$gp/ws/terminal?agentId=backend",
            request = { header("X-Session-Token", "sess-member") },
        ) {
            send(input("hello\n"))
            assertTrue(readUntil("GOT:hello"), "② the member's shell was live through the gateway before the revoke")
            grants.revoke("backend", "id-member") // tears socket #1 (S5 ②) AND removes the capability
            withTimeout(10_000) { closeReason.await() } // socket #1 is torn (grant_revoked) — drain it
        }
        // Reconnect #2 — the SAME (now-revoked) member, carrying ?since as an S4 reconnect would → must be REFUSED.
        client().webSocket(
            "ws://127.0.0.1:$gp/ws/terminal?agentId=backend&since=0",
            request = { header("X-Session-Token", "sess-member") },
        ) {
            val reason = withTimeout(10_000) { closeReason.await() }
            assertEquals(
                CloseReason.Codes.VIOLATED_POLICY.code, reason?.code,
                "② ★ after a revoke, the SAME member's ?since-reconnect is REFUSED (1008) across the hop — the revoke is " +
                    "durable, not cosmetic; ?since is no reconnect bypass. Got: $reason",
            )
            assertEquals(
                "forbidden", reason?.message,
                "② …specifically 1008 forbidden (the per-connect gate re-check denies the revoked grant), not a new shell",
            )
        }
    }

    // ---- ②b attach-corner — the literal "weiterlesen": a still-LIVE motor session cannot be reattached after revoke ----

    @Test
    fun revokedMember_cannotReattachToLiveMotorSession_afterRevoke() = runBlocking {
        val grants = InMemoryTerminalGrants().apply { grant("backend", "id-member") }
        val mgr = newMgr()
        // A motor-owned PTY that OUTLIVES any viewer socket (no owning socket): seed it so there is real scrollback to
        // "keep reading". This is the session a ?since-reconnect would reattach to and replay.
        mgr.spawnInteractive("backend", 80, 24, listOf(fakeTui().absolutePath)) { }
        cleanups += { mgr.close("backend") }
        mgr.write("backend", "seed\n".toByteArray()) // → the TUI echoes GOT:seed into the replay buffer
        val gp = startGateway(startTerminalHub(mgr, grants))

        // Connect #1 — the granted member ATTACHES to the live motor session and reads the seed (weiterlesen works when
        // authorized), then the grant is revoked (viewer socket torn; the MOTOR session lives on).
        client().webSocket(
            "ws://127.0.0.1:$gp/ws/terminal?agentId=backend&since=0",
            request = { header("X-Session-Token", "sess-member") },
        ) {
            assertTrue(
                readUntil("GOT:seed"),
                "②b the granted member attached to the live motor session and read its scrollback (weiterlesen) through the gateway",
            )
            grants.revoke("backend", "id-member")
            withTimeout(10_000) { closeReason.await() } // viewer socket torn; the motor PTY is NOT killed (viewer detach)
        }
        // Reconnect #2 — the revoked member tries to REATTACH to the still-live motor session via ?since → REFUSED at the
        // gate BEFORE any attach/replay. The live session with GOT:seed is right there, and the revoke still blocks it.
        client().webSocket(
            "ws://127.0.0.1:$gp/ws/terminal?agentId=backend&since=0",
            request = { header("X-Session-Token", "sess-member") },
        ) {
            val reason = withTimeout(10_000) { closeReason.await() }
            assertEquals(
                CloseReason.Codes.VIOLATED_POLICY.code, reason?.code,
                "②b ★ after a revoke, the member CANNOT reattach to the still-LIVE motor session via ?since — refused " +
                    "(1008) at the gate before any replay. The revoke blocks weiterlesen even with a live session present. Got: $reason",
            )
            assertEquals("forbidden", reason?.message, "②b …1008 forbidden (per-connect gate), not a reattach")
        }
    }
}
