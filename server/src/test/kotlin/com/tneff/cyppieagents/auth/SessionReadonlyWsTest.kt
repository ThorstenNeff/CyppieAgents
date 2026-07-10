package com.tneff.cyppieagents.auth

import com.tneff.cyppieagents.FakeGit
import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.BootedPlatform
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.installPlatform
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.websocket.CloseReason
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import io.ktor.client.request.post
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.fail
import kotlin.test.assertTrue

/**
 * CYP-188 B — the **session read-tier** for WebSockets. Complements [WsMcpAuthzNetTest] (A, the no-cred
 * fail-closed invariant) with the tier cases: a verified human **MEMBER session** (no operator token) is
 * ADMITTED on the read-only sockets (`/ws/comm`, `/ws/events`, `/ws/lifecycle`) — same tier as their REST
 * reads — but REJECTED on the machine-only sockets (`/ws/agent` = operator/agent-self, `/ws/hub` = agent-only),
 * so no session path is weaker than the `/api` guard.
 */
class SessionReadonlyWsTest {

    private fun authDeps(store: SqliteRoleStore) = AuthDeps(
        tokens = TokenRegistry(emptyMap(), operatorToken = "tok-op"),
        idp = FakeIdentityProvider(
            mapOf(
                "sess-alice" to ResolvedIdentity("alice-op", verified = true), // first-verified → OPERATOR
                "sess-carol" to ResolvedIdentity("carol-mem", verified = true), // later → MEMBER
            ),
        ),
        roles = store, nowMs = { 1_000L },
    )

    private suspend fun ApplicationTestBuilder.bootstrapOperatorThenMember() {
        client.get("/api/auth/me") { header("X-Session-Token", "sess-alice") }
        client.get("/api/auth/me") { header("X-Session-Token", "sess-carol") }
    }

    /**
     * CYP-372 — **Zulassung wird bewiesen, nicht ausgesessen.**
     *
     * Die frühere Fassung wartete mit `withTimeoutOrNull(1_500)` auf ein `closeReason`, das bei Erfolg **nie
     * kommt**, und las das Ausbleiben als „zugelassen". Das positive Ergebnis entstand dadurch, dass der Test
     * die Zeit **überlebte** — 4,5 s Laufzeit. Ein Socket, der erst bei 1,6 s geschlossen wird, hieß dort
     * weiterhin „zugelassen"; genau das konnte der alte Test nicht sehen.
     *
     * Nach dem Muster von `WireHandshakeTimeoutTest.handshookInTime_isNotReaped`: **etwas tun, statt nichts zu
     * beobachten.** Gemessen liefert der Server auf den Lese-Sockets von sich aus:
     *
     * ```
     * /ws/comm       TEXT nach 1 ms   (Kanal-Snapshot)
     * /ws/lifecycle  TEXT nach 1 ms   (Status-Snapshot)
     * /ws/events     nichts           -> braucht einen echten Reiz
     * /ws/agent, /ws/hub (abgelehnt)  ClosedReceiveChannelException, sofort
     * ```
     *
     * Drei Ausgänge, und **keiner ist ein Timeout**: `ADMITTED` = ein Frame kam an · `REJECTED` = der Kanal war
     * zu · **Frist gerissen = `fail()`**, nie ein Ergebnis.
     *
     * > `withTimeout` **wirft**. `withTimeoutOrNull` liefert `null` und verwandelt einen Hang in eine Antwort.
     */
    private enum class WsOutcome { ADMITTED, REJECTED }

    /** Der Beweis kommt in Millisekunden. Wer diese Frist reißt, hängt — das ist ein Fehler, kein Ergebnis. */
    private val livenessDeadlineMs = 1_000L

    private suspend fun ApplicationTestBuilder.wsOutcome(path: String, sessionToken: String?): WsOutcome {
        val wsClient = createClient { install(ClientWebSockets) }
        var outcome: WsOutcome? = null
        try {
            wsClient.webSocket(path, request = { if (sessionToken != null) header("X-Session-Token", sessionToken) }) {
                // /ws/events pusht nichts von selbst: einen echten Server-Event ausloesen (Operator, REST), den ein
                // zugelassener Lese-Socket ausliefern MUSS. Der ausgelieferte Frame ist der Beweis, nicht das Schweigen.
                if (path.startsWith("/ws/events")) {
                    launch { client.post("/api/agents/backend/restart") { header("Authorization", "Bearer tok-op") } }
                }
                outcome = try {
                    withTimeout(livenessDeadlineMs) { incoming.receive() }
                    WsOutcome.ADMITTED
                } catch (e: ClosedReceiveChannelException) {
                    WsOutcome.REJECTED
                }
            }
        } catch (e: TimeoutCancellationException) {
            fail("$path: weder ein Frame noch ein Schliessen innerhalb $livenessDeadlineMs ms — der Socket haengt. " +
                "Ein Hang darf nie als 'zugelassen' durchgehen (CYP-372).")
        } catch (e: ClosedReceiveChannelException) {
            return WsOutcome.REJECTED
        } catch (e: Exception) {
            return WsOutcome.REJECTED // Handshake abgelehnt (1008 vor dem Upgrade)
        }
        return outcome ?: fail("$path: kein Ausgang bestimmt")
    }

    /** ZUGELASSEN wird **positiv belegt** — durch einen ausgelieferten Frame, nicht durch ausbleibendes Schliessen. */
    private suspend fun ApplicationTestBuilder.memberAdmitted(path: String): WsOutcome =
        wsOutcome(path, sessionToken = "sess-carol")

    private suspend fun ApplicationTestBuilder.wsAdmitted(path: String, sessionToken: String? = null): WsOutcome =
        wsOutcome(path, sessionToken)

    @Test
    fun cyp230_operatorSessionAdmitted_memberAndInvalidTokenRejected_onWsAgent() = testApplication {
        // CYP-196: OPERATOR is granted ONLY to the pinned bootstrap identity — pin alice-op so she is a real OPERATOR.
        val db = Files.createTempFile("cyp230-ws", ".db"); val store = SqliteRoleStore(db, bootstrapOperatorId = "alice-op")
        application { installPlatform(bootFake(), authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()
        bootstrapOperatorThenMember() // alice → OPERATOR, carol → MEMBER

        // CYP-230: the tokenless public SPA's verified OPERATOR session opens /ws/agent (the same-origin cookie
        // path — no agent secret ships to the public client).
        assertEquals(WsOutcome.ADMITTED, wsAdmitted("/ws/agent?agentId=backend", sessionToken = "sess-alice"), "OPERATOR session must open /ws/agent")
        // A verified MEMBER session must NOT (only an operator watches another agent's stream).
        assertEquals(WsOutcome.REJECTED, wsAdmitted("/ws/agent?agentId=backend", sessionToken = "sess-carol"), "MEMBER session must not open /ws/agent")
        // The pre-fix public-SPA failure: a dev-placeholder/invalid token with NO session → 1008 (VIOLATED_POLICY).
        assertEquals(WsOutcome.REJECTED, wsAdmitted("/ws/agent?agentId=backend&token=dev-token-bogus"), "invalid token + no session must be rejected")

        store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun memberSession_admittedOnReadWs_rejectedOnMachineWs() = testApplication {
        val db = Files.createTempFile("cyp188b-ws", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake(), authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()
        bootstrapOperatorThenMember() // alice → OPERATOR, carol → MEMBER

        // Read-only sockets: a verified MEMBER session is ADMITTED (same tier as the REST reads).
        assertEquals(WsOutcome.ADMITTED, memberAdmitted("/ws/comm"), "MEMBER session must be admitted on /ws/comm (ACL-filtered)")
        assertEquals(WsOutcome.ADMITTED, memberAdmitted("/ws/events"), "MEMBER session must be admitted on /ws/events (MEMBER-tier)")
        assertEquals(WsOutcome.ADMITTED, memberAdmitted("/ws/lifecycle"), "MEMBER session must be admitted on /ws/lifecycle (content-free status)")

        // Machine-only sockets: a MEMBER session is REJECTED (no session path weaker than the /api guard).
        assertEquals(WsOutcome.REJECTED, memberAdmitted("/ws/agent?agentId=backend"), "MEMBER session must NOT open /ws/agent (operator/agent-self)")
        assertEquals(WsOutcome.REJECTED, memberAdmitted("/ws/hub"), "MEMBER session must NOT open /ws/hub (agent-only)")

        store.close(); Files.deleteIfExists(db)
    }

    /**
     * CYP-372: Der Fake spricht **eine** Zeile. Ein Agenten-Socket ohne jede Ausgabe kann seine Zulassung nur
     * durch *ausbleibendes Schliessen* belegen — also durch Abwesenheit. Mit einer Zeile belegt er sie durch
     * einen ausgelieferten Frame. Der Kanal bleibt offen, solange der Prozess lebt.
     *
     * CYP-371: `destroy()` schliesst den stdout-Kanal — wie ein echter Prozess unter SIGTERM. Seit CYP-371 wartet
     * `closeAndAwait` (destroy → JOIN, kein cancel) darauf, dass der Reader **EOF** erreicht, statt ihn hart zu
     * canceln. Der frühere `flow { emit(…); delay(MAX) }` mit `destroy(){}`-No-op modellierte einen Prozess, der
     * SIGTERM ignoriert und nie EOF liefert — der Join lief dann in seinen 5s-Backstop und riss CYP-372s
     * 1000ms-Liveness-Frist (`restart` → `removeAndAwait` → `closeAndAwait`). Ein bei `destroy()` geschlossener
     * Channel modelliert das echte Prozessende und ist das Muster jedes anderen Channel-Fakes im Repo.
     */
    private class FakeProcess : AgentProcess {
        private val lines = Channel<String>(Channel.UNLIMITED).apply {
            trySend("""{"type":"system","subtype":"init","session_id":"probe-1"}""")
        }
        override val stdoutLines: Flow<String> = lines.receiveAsFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() { lines.close() } // real destroy closes stdout → the reader reaches EOF (CYP-371)
    }

    private fun bootFake(): BootedPlatform {
        val config = PlatformConfig(
            RepoConfig("git@github.com:org/repo.git", "main"),
            agents = listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("backend", "BE", Role.WORKER)),
        )
        val secrets = Secrets(mapOf("tok-backend" to "backend"), operatorToken = "tok-op", apiKey = null)
        val gitRoot = Files.createTempDirectory("cyp188b-test").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> FakeProcess() }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
    }
}
