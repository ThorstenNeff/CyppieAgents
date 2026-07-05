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
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
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

    /** true = the MEMBER session is ADMITTED (the socket stays open); false = closed VIOLATED_POLICY / rejected. */
    private suspend fun ApplicationTestBuilder.memberAdmitted(path: String): Boolean {
        val wsClient = createClient { install(ClientWebSockets) }
        return try {
            var rejected = false
            wsClient.webSocket(path, request = { header("X-Session-Token", "sess-carol") }) {
                val reason = withTimeoutOrNull(1_500) { closeReason.await() }
                rejected = reason?.code == CloseReason.Codes.VIOLATED_POLICY.code
            }
            !rejected
        } catch (e: Exception) {
            false // handshake rejected / closed → not admitted
        }
    }

    /** true = admitted (authorize passed — not closed VIOLATED_POLICY); optional session token + query token. */
    private suspend fun ApplicationTestBuilder.wsAdmitted(path: String, sessionToken: String? = null): Boolean {
        val wsClient = createClient { install(ClientWebSockets) }
        return try {
            var rejected = false
            wsClient.webSocket(path, request = { if (sessionToken != null) header("X-Session-Token", sessionToken) }) {
                val reason = withTimeoutOrNull(1_500) { closeReason.await() }
                rejected = reason?.code == CloseReason.Codes.VIOLATED_POLICY.code
            }
            !rejected
        } catch (e: Exception) {
            false
        }
    }

    @Test
    fun cyp230_operatorSessionAdmitted_memberAndInvalidTokenRejected_onWsAgent() = testApplication {
        // CYP-196: OPERATOR is granted ONLY to the pinned bootstrap identity — pin alice-op so she is a real OPERATOR.
        val db = Files.createTempFile("cyp230-ws", ".db"); val store = SqliteRoleStore(db, bootstrapOperatorId = "alice-op")
        application { installPlatform(bootFake(), authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()
        bootstrapOperatorThenMember() // alice → OPERATOR, carol → MEMBER

        // CYP-230: the tokenless public SPA's verified OPERATOR session opens /ws/agent (the same-origin cookie
        // path — no agent secret ships to the public client).
        assertTrue(wsAdmitted("/ws/agent?agentId=backend", sessionToken = "sess-alice"), "OPERATOR session must open /ws/agent")
        // A verified MEMBER session must NOT (only an operator watches another agent's stream).
        assertFalse(wsAdmitted("/ws/agent?agentId=backend", sessionToken = "sess-carol"), "MEMBER session must not open /ws/agent")
        // The pre-fix public-SPA failure: a dev-placeholder/invalid token with NO session → 1008 (VIOLATED_POLICY).
        assertFalse(wsAdmitted("/ws/agent?agentId=backend&token=dev-token-bogus"), "invalid token + no session must be rejected")

        store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun memberSession_admittedOnReadWs_rejectedOnMachineWs() = testApplication {
        val db = Files.createTempFile("cyp188b-ws", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake(), authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()
        bootstrapOperatorThenMember() // alice → OPERATOR, carol → MEMBER

        // Read-only sockets: a verified MEMBER session is ADMITTED (same tier as the REST reads).
        assertTrue(memberAdmitted("/ws/comm"), "MEMBER session must be admitted on /ws/comm (ACL-filtered)")
        assertTrue(memberAdmitted("/ws/events"), "MEMBER session must be admitted on /ws/events (MEMBER-tier)")
        assertTrue(memberAdmitted("/ws/lifecycle"), "MEMBER session must be admitted on /ws/lifecycle (content-free status)")

        // Machine-only sockets: a MEMBER session is REJECTED (no session path weaker than the /api guard).
        assertFalse(memberAdmitted("/ws/agent?agentId=backend"), "MEMBER session must NOT open /ws/agent (operator/agent-self)")
        assertFalse(memberAdmitted("/ws/hub"), "MEMBER session must NOT open /ws/hub (agent-only)")

        store.close(); Files.deleteIfExists(db)
    }

    private class FakeProcess : AgentProcess {
        override val stdoutLines: Flow<String> = emptyFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
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
