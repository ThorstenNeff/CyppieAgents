package com.tneff.cyppieagents.auth

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.TerminalClientFrame
import com.tneff.cyppieagents.model.TerminalInput
import com.tneff.cyppieagents.model.TerminalOutput
import com.tneff.cyppieagents.model.TerminalServerFrame
import com.tneff.cyppieagents.pty.PtyManager
import com.tneff.cyppieagents.routing.NoTerminalGrants
import com.tneff.cyppieagents.routing.TerminalGrantStore
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.terminalSocket
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-394 — `/ws/terminal` is a **WRITE-tier** surface (a bidirectional PTY = code-exec in the worktree), so it
 * is gated by [terminalSocket]'s capability check, NOT the read-tier `wsReaderOrNull`. This is the enforced
 * counterpart to [MemberStreamDenyTest] (which denies a MEMBER the read-only `/ws/agent` stream): here a MEMBER
 * — and a participant token — must be denied the far more powerful interactive shell, while an operator is
 * admitted.
 *
 * The three Auftraggeber hardening auflagen, each a tooth:
 *  1. **default-DENY** — the empty prod store [NoTerminalGrants] denies every non-operator (MEMBER, participant).
 *  2. **operator bypass, store-independent** — an operator opens the terminal EVEN against a store that grants
 *     nobody (else an empty store would lock the operator out too).
 *  3. **revoke kills the LIVE session** — a grant revoked while a shell is open ends the running session
 *     server-side (`grant_revoked`), not just the next connect.
 *
 * Mutation: reverting the gate to `wsReaderOrNull` reddens the MEMBER and participant teeth (each admitted as a
 * reader); dropping the kill-switch watcher reddens the revoke tooth.
 */
class MemberTerminalDenyTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    /** A fake interactive TUI that echoes each line back as `GOT:<line>` — proves a spawned PTY is live. */
    private val FAKE_TUI = """
        #!/usr/bin/env bash
        while IFS= read -r line; do printf 'GOT:%s\n' "${'$'}line"; done
    """.trimIndent()

    private fun fakeTui(): File =
        Files.createTempFile("cyp394-tui", ".sh").toFile().apply { writeText(FAKE_TUI); setExecutable(true) }

    /** Registry (operator + one agent token) shared with the [AuthDeps] below so BOTH token axes agree. */
    private fun registry() = TokenRegistry(mapOf("tok-backend" to "backend"), operatorToken = "tok-op")

    /**
     * The auth harness: an operator SESSION (pinned `id-op` → OPERATOR) and a MEMBER SESSION (`id-member`),
     * plus a participant-token store. `sess-op`/`sess-member` are the fake Kratos session values.
     */
    private fun deps(reg: TokenRegistry, participant: ParticipantTokenStore) = AuthDeps(
        tokens = reg,
        idp = FakeIdentityProvider(
            mapOf(
                "sess-op" to ResolvedIdentity("id-op", verified = true, aal2 = true),
                "sess-member" to ResolvedIdentity("id-member", verified = true),
            ),
        ),
        roles = InMemoryRoleStore(bootstrapOperatorId = "id-op"),
        nowMs = { 1_000L },
        participantTokens = participant,
    )

    private fun ApplicationTestBuilder.installTerminal(reg: TokenRegistry, deps: AuthDeps, grants: TerminalGrantStore, command: File) {
        val mgr = PtyManager(
            worktreeDirOf = { Files.createTempDirectory("cyp394-wt").toFile() },
            resolveApiKey = { null },
            scope = scope,
            command = listOf(command.absolutePath),
        )
        application {
            install(WebSockets)
            routing { terminalSocket({ mgr }, knowsAgent = { it == "backend" }, registry = reg, deps = deps, grants = grants) }
        }
    }

    private fun wsClient(b: ApplicationTestBuilder) = b.createClient { install(ClientWebSockets) }
    private fun input(text: String) =
        Frame.Text(CommJson.encodeToString(TerminalClientFrame.serializer(), TerminalInput(Base64.getEncoder().encodeToString(text.toByteArray())) as TerminalClientFrame))
    private fun DefaultClientWebSocketSession.decode(f: Frame): TerminalServerFrame =
        CommJson.decodeFromString(TerminalServerFrame.serializer(), (f as Frame.Text).readText())

    // ---- 1. default-DENY: the empty prod store denies every non-operator ----

    @Test
    fun memberSession_cannotOpenTerminal_failClosed() = testApplication {
        val reg = registry()
        installTerminal(reg, deps(reg, ParticipantTokenStore { 1_000L }), NoTerminalGrants, fakeTui())
        val client = wsClient(this)
        // A human MEMBER carries a valid Kratos session, but a terminal is code-exec (WRITE) → default-DENY.
        client.webSocket("/ws/terminal?agentId=backend", request = { header("X-Session-Token", "sess-member") }) {
            val reason = withTimeout(10_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code, "a MEMBER session must NOT open the interactive terminal (WRITE-tier, empty store → deny)")
        }
    }

    @Test
    fun participantToken_cannotOpenTerminal_failClosed() = testApplication {
        val reg = registry()
        val participant = ParticipantTokenStore { 1_000L }
        val raw = participant.mint("byo-consumer-1")
        installTerminal(reg, deps(reg, participant), NoTerminalGrants, fakeTui())
        val client = wsClient(this)
        // A participant token is read-only by construction — it reaches read streams via canRead, never a shell.
        // Via `?token=` (a browser WS can't set Authorization).
        client.webSocket("/ws/terminal?agentId=backend&token=$raw") {
            val reason = withTimeout(10_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code, "a participant token (read-only) must NOT open the interactive terminal")
        }
    }

    // ---- 2. operator bypass, store-independent (allowed despite an empty store) ----

    @Test
    fun operatorToken_opensTerminal_andInputReachesPty() = testApplication {
        val reg = registry()
        installTerminal(reg, deps(reg, ParticipantTokenStore { 1_000L }), NoTerminalGrants, fakeTui())
        val client = wsClient(this)
        // NoTerminalGrants grants nobody, yet the operator token is admitted (store-independent) AND can write.
        client.webSocket("/ws/terminal?agentId=backend&token=tok-op") {
            send(input("hi\n"))
            assertTrue(readUntil("GOT:hi"), "the operator opened the terminal and its input reached the PTY (bypasses the empty store)")
        }
    }

    @Test
    fun operatorSession_opensTerminal_andInputReachesPty() = testApplication {
        val reg = registry()
        installTerminal(reg, deps(reg, ParticipantTokenStore { 1_000L }), NoTerminalGrants, fakeTui())
        val client = wsClient(this)
        // The CYP-230 browser-operator branch: a verified OPERATOR Kratos session opens the terminal.
        client.webSocket("/ws/terminal?agentId=backend", request = { header("X-Session-Token", "sess-op") }) {
            send(input("yo\n"))
            assertTrue(readUntil("GOT:yo"), "a verified OPERATOR session opened the terminal and its input reached the PTY")
        }
    }

    // ---- 3. revoke kills the LIVE session server-side ----

    @Test
    fun revokeWhileOpen_endsTheLiveSession_serverSide() = testApplication {
        val reg = registry()
        // A test double that DOES grant the member — so a delegable session is admitted and can then be revoked.
        val grants = FakeGrants().apply { grant("backend", "id-member") }
        installTerminal(reg, deps(reg, ParticipantTokenStore { 1_000L }), grants, fakeTui())
        val client = wsClient(this)
        client.webSocket("/ws/terminal?agentId=backend", request = { header("X-Session-Token", "sess-member") }) {
            send(input("hello\n"))
            val seen = StringBuilder()
            var revoked = false
            withTimeout(15_000) {
                for (frame in incoming) {
                    val f = decode(frame)
                    if (f is TerminalOutput) {
                        seen.append(String(Base64.getDecoder().decode(f.dataBase64)))
                        // Once the granted member's shell is proven live, revoke the grant WHILE it is open.
                        if (!revoked && seen.contains("GOT:hello")) {
                            revoked = true
                            grants.revoke("backend", "id-member")
                        }
                    }
                }
            }
            assertTrue(revoked, "the granted member's shell was live (input echoed) before the revoke")
            val reason = closeReason.await()
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code, "revoking the grant ends the LIVE session server-side")
            assertEquals("grant_revoked", reason?.message, "…specifically grant_revoked (kill-on-revoke, not just the next connect)")
        }
    }

    /** Read [TerminalOutput] frames until [needle] appears (or the socket closes). */
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

    /**
     * CYP-394 — a test grant store that actually grants (so the kill-on-revoke path is exercised e2e). Production
     * uses [NoTerminalGrants] (empty). [revoke] fires every tracked kill for (agentId, subject) — the server-side
     * kill-switch [terminalSocket] registered on connect — so the live shell is closed immediately.
     */
    private class FakeGrants : TerminalGrantStore {
        private val granted = ConcurrentHashMap<Pair<String, String>, Boolean>()
        private val kills = ConcurrentHashMap<Pair<String, String>, CopyOnWriteArrayList<() -> Unit>>()

        fun grant(agentId: String, subject: String) { granted[agentId to subject] = true }
        fun revoke(agentId: String, subject: String) {
            granted.remove(agentId to subject)
            kills[agentId to subject]?.toList()?.forEach { it() }
        }

        override fun mayOpen(agentId: String, subject: String): Boolean = granted[agentId to subject] == true
        override fun track(agentId: String, subject: String, kill: () -> Unit): AutoCloseable {
            val key = agentId to subject
            val list = kills.getOrPut(key) { CopyOnWriteArrayList() }
            list.add(kill)
            return AutoCloseable { list.remove(kill) }
        }
    }
}
