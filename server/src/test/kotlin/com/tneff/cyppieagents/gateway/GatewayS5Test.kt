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
 * CYP-638 **S5 acceptance tooth** — `/ws/terminal` operator-gate + kill-on-revoke survival **across the A2 gateway
 * hop**, built as the merge-blocking security proof (design §11: "the terminal auth survival (S5) … are the
 * merge-blocking security teeth … terminal revoke tears the socket"). Worst-case guarded: **a revoked operator/member
 * keeps a live PTY shell** — behind `/ws/terminal` is arbitrary code-exec in the worktree, so this is the sharpest
 * edge in the gateway.
 *
 * **Harness (fuses [GatewayS2Test]'s real-gateway-in-front-of-real-hub with [com.tneff.cyppieagents.auth.MemberTerminalDenyTest]'s
 * real `terminalSocket` + PtyManager + kill-on-revoke):** a REAL [gatewayModule] server proxies to a REAL hub that
 * mounts the production `terminalSocket` (gate + real [InMemoryTerminalGrants] + a live bash PTY echoing `GOT:<line>`).
 * A real CIO WS client drives `/ws/terminal` THROUGH the gateway. Nothing is stubbed at the boundary under test — the
 * property is measured end-to-end across the hop.
 *
 * **Four quadrants (CYP-655 discipline):**
 *  • **ANCHOR** ([anchor_operatorOpensTerminal_throughGateway_shellIsLive]) — an operator opens a LIVE terminal through
 *    the gateway (input reaches the PTY). Positive control: the harness drives a real terminal across the A2 hop, so a
 *    green gate/revoke below is meaningful, not a broken-pipe artifact. Also the operator-gate's ALLOW leg.
 *  • **① gate across the hop** ([gate_nonOperatorMember_isRefusedAtEdge_throughGateway],
 *    [gate_forgedOperatorMarker_isRefusedAtEdge_throughGateway]) — the gateway forwards the credential VERBATIM and
 *    grants ZERO implicit trust; the HUB re-verifies and refuses (1008). A genuinely-authenticated non-operator MEMBER,
 *    and a forged "already-operator" marker with no real credential (the loopback-bridge T2 analog), are BOTH refused
 *    at the client across the hop.
 *  • **② ★ revoke tears the socket across the hop** ([revokeWhileLive_tearsTheSocket_acrossTheGatewayHop]) — the
 *    load-bearing security direction. A grant revoked while the shell is live fires the hub-side kill → `terminalSocket`
 *    closes 1008 `grant_revoked` → `relayFrames` propagates the close UNMASKED across the gateway → the browser's socket
 *    dies with 1008 `grant_revoked`. **No live shell survives a revoke through the gateway.**
 *
 * **Property, not impl:** the gateway's job (A2) is verbatim-forward + close-propagation; the gate + kill live at the
 * hub. This tooth pins that the composed property SURVIVES the hop. If `/ws/terminal` is already carried by S2's
 * `WS_CHANNELS` single-sourcing + `relayFrames`' unmasked close, these are GREEN by construction (an S5 regression-guard).
 * The mutation the PO will run — the gateway SWALLOWS/holds the revoke-close (propagate NORMAL instead of the hub reason)
 * — must redden ②; verified by a real `relayFrames` mutation (reported, not committed).
 *
 * **Where the tooth stops:** it pins the gate + revoke-tear across the gateway↔hub↔PTY path with a real bash PTY. It
 * does NOT re-prove the hub-side gate/kill logic in isolation (that is [MemberTerminalDenyTest] / [Cyp421TerminalGrantRaceTest]),
 * nor the S6 cleartext-boundary hardening (no PTY-byte logging — a separate story/tooth).
 */
class GatewayS5Test {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val servers = mutableListOf<EmbeddedServer<*, *>>()
    private var wsClient: HttpClient? = null

    @AfterTest fun tearDown() {
        runCatching { wsClient?.close() }
        servers.forEach { runCatching { it.stop(0, 0) } }
        scope.cancel()
    }

    /** A fake interactive TUI that echoes each line back as `GOT:<line>` — proves a spawned PTY is live. */
    private val FAKE_TUI = """
        #!/usr/bin/env bash
        while IFS= read -r line; do printf 'GOT:%s\n' "${'$'}line"; done
    """.trimIndent()
    private fun fakeTui(): File =
        Files.createTempFile("cyp638s5-tui", ".sh").toFile().apply { writeText(FAKE_TUI); setExecutable(true) }

    private fun registry() = TokenRegistry(mapOf("tok-backend" to "backend"), operatorToken = "tok-op", loopbackPosture = true)
    private fun deps(reg: TokenRegistry) = AuthDeps(
        tokens = reg,
        idp = FakeIdentityProvider(
            mapOf(
                "sess-op" to ResolvedIdentity("id-op", verified = true),
                "sess-member" to ResolvedIdentity("id-member", verified = true),
            ),
        ),
        roles = InMemoryRoleStore(bootstrapOperatorId = "id-op"),
        nowMs = { 1_000L },
        participantTokens = ParticipantTokenStore { 1_000L },
    )

    /** A REAL hub mounting the production `terminalSocket` (gate + real [InMemoryTerminalGrants] + a live bash PTY) —
     *  the far end of the A2 hop. Returns the hub port. */
    private fun startTerminalHub(grants: InMemoryTerminalGrants): Int {
        val reg = registry()
        val mgr = PtyManager(
            worktreeDirOf = { Files.createTempDirectory("cyp638s5-wt").toFile() },
            resolveApiKey = { null },
            scope = scope,
            command = listOf(fakeTui().absolutePath),
        )
        val hub = embeddedServer(Netty, port = 0) {
            install(WebSockets)
            routing { terminalSocket({ mgr }, knowsAgent = { it == "backend" }, registry = reg, deps = deps(reg), grants = grants) }
        }.start(wait = false)
        servers += hub
        return runBlocking { hub.engine.resolvedConnectors().first().port }
    }

    /** The REAL [gatewayModule] proxying to the hub. Returns the gateway (browser-facing) port. */
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

    // ---- ANCHOR — an operator opens a LIVE terminal THROUGH the gateway (positive control + the gate's ALLOW leg) ----

    @Test
    fun anchor_operatorOpensTerminal_throughGateway_shellIsLive() = runBlocking {
        val gp = startGateway(startTerminalHub(InMemoryTerminalGrants()))
        client().webSocket("ws://127.0.0.1:$gp/ws/terminal?agentId=backend&token=tok-op") {
            send(input("hi\n"))
            assertTrue(
                readUntil("GOT:hi"),
                "ANCHOR: an operator opens the terminal THROUGH the gateway and its input reaches the LIVE PTY — the " +
                    "harness drives a real terminal across the A2 hop, so the gate/revoke verdicts below are meaningful.",
            )
        }
    }

    // ---- ① gate across the hop — the hub decides, the gateway grants zero implicit trust ----

    @Test
    fun gate_nonOperatorMember_isRefusedAtEdge_throughGateway() = runBlocking {
        val gp = startGateway(startTerminalHub(InMemoryTerminalGrants())) // empty store → the member is ungranted
        client().webSocket(
            "ws://127.0.0.1:$gp/ws/terminal?agentId=backend",
            request = { header("X-Session-Token", "sess-member") },
        ) {
            val reason = withTimeout(10_000) { closeReason.await() }
            assertEquals(
                CloseReason.Codes.VIOLATED_POLICY.code, reason?.code,
                "① a genuinely-authenticated non-operator MEMBER is refused (1008) across the gateway hop — the gateway " +
                    "forwards the session verbatim, the HUB re-verifies write-tier and denies (default-DENY). Got: $reason",
            )
        }
    }

    @Test
    fun gate_forgedOperatorMarker_isRefusedAtEdge_throughGateway() = runBlocking {
        val gp = startGateway(startTerminalHub(InMemoryTerminalGrants()))
        // The loopback-bridge T2 analog: markers a naive edge MIGHT read as "already operator" + a bogus token, but NO
        // real operator credential. The gateway grants zero implicit trust (forwards verbatim); the hub re-verifies the
        // real token and IGNORES transport claims → refused. A gateway that injected/trusted such a marker would leak.
        client().webSocket(
            "ws://127.0.0.1:$gp/ws/terminal?agentId=backend&token=forged-not-an-operator",
            request = {
                header("X-Already-Operator", "true")
                header("X-Forwarded-Role", "operator")
            },
        ) {
            val reason = withTimeout(10_000) { closeReason.await() }
            assertEquals(
                CloseReason.Codes.VIOLATED_POLICY.code, reason?.code,
                "① T2-analog: a forged operator marker + bogus token (no real credential) over the gateway is still " +
                    "refused (1008) — no implicit trust survives the hop. Got: $reason",
            )
        }
    }

    // ---- ② ★ revoke TEARS the socket across the hop — the load-bearing security direction ----

    @Test
    fun revokeWhileLive_tearsTheSocket_acrossTheGatewayHop() = runBlocking {
        val grants = InMemoryTerminalGrants().apply { grant("backend", "id-member") } // grant so a delegable shell opens
        val gp = startGateway(startTerminalHub(grants))
        client().webSocket(
            "ws://127.0.0.1:$gp/ws/terminal?agentId=backend",
            request = { header("X-Session-Token", "sess-member") },
        ) {
            send(input("hello\n"))
            val seen = StringBuilder()
            var revoked = false
            withTimeout(15_000) {
                for (frame in incoming) {
                    val f = decode(frame)
                    if (f is TerminalOutput) {
                        seen.append(String(Base64.getDecoder().decode(f.dataBase64)))
                        if (!revoked && seen.contains("GOT:hello")) {
                            revoked = true
                            grants.revoke("backend", "id-member") // ★ revoke WHILE the shell is live through the gateway
                        }
                    }
                }
            }
            assertTrue(revoked, "② the granted member's shell was LIVE (echo seen through the gateway) before the revoke")
            val reason = withTimeout(10_000) { closeReason.await() }
            assertEquals(
                CloseReason.Codes.VIOLATED_POLICY.code, reason?.code,
                "② ★ a revoke while the shell is live TEARS the socket AND the tear PROPAGATES across the gateway hop to " +
                    "the browser (relayFrames unmasked close) — no live shell survives a revoke through the A2 hop. Got: $reason",
            )
            assertEquals(
                "grant_revoked", reason?.message,
                "② ★ …specifically 1008 grant_revoked reaches the browser THROUGH the gateway (the exact hub reason, unmasked)",
            )
        }
    }
}
