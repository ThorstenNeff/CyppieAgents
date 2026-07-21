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
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-186 (S18 kick-off / BE1) — the **MEMBER-403 deny matrix over the REAL routing tree**. Boots the real
 * platform with an injected fake idp (alice = first-verified → OPERATOR; carol = later-verified → MEMBER),
 * walks every `/api` endpoint, and for every route the **OPERATOR human reaches past the guard** (not a
 * participant/token route, not the public/settings allowlist) asserts the **MEMBER human gets 403**
 * (`operator_required` — authz, not merely 401). No hand-list: a route mis-gated as MEMBER (or public) that an
 * operator can mutate would return 2xx for carol and red this. Complements [MemberTierGuardTest] (the guard
 * unit) with real-route coverage — the same idea as [ProtectedRouteEnumerationTest] for the no-credential net.
 */
class MemberTier403MatrixTest {

    /** Public + MEMBER-permitted routes a MEMBER is allowed to reach (not part of the operator deny set). */
    private val memberAllowlist = setOf(
        "GET /api/health", "GET /api/agents", "GET /api/auth/me",
        "POST /api/auth/settings/password", "POST /api/auth/settings/email",
        // CYP-186 BE2 — MEMBER-permitted READS (ACL-filtered / masked / secret-free metadata), not operator-deny:
        "GET /api/channels", "GET /api/channels/{id}/messages", "GET /api/inbox", "GET /api/acl",
        // CYP-273 — the composer-enable seam: the WRITABLE-subset read is the same participant-read tier as
        // `/api/channels` (ACL-filtered, content-free channel ids), so a MEMBER reaches it (200), not tier-denied.
        "GET /api/channels/writable",
        // CYP-779 — the agent-granularity composer-enable seam, same PARTICIPANT read tier as /channels/writable
        // (ACL-filtered, content-free agent ids). A MEMBER reaches it (200), not tier-denied.
        "GET /api/agents/writable",
        "GET /api/config/repo", "GET /api/config/apikey", "GET /api/events",
        // CYP-417 (S-G): the capacity read is MEMBER-tier (content-free counters, secret-free) — a MEMBER reaches
        // it (200), like the other reads above; the hard capacity GATE is server-side in the spawn path, not here.
        "GET /api/capacity",
        // CYP-487: the server-clock read is now READ-TIER (requireCommReader, was token-only requireParticipant —
        // the CYP-320-class cutover 401 for a cookie-session browser). Content-free (only serverNowMs), member-
        // reachable like GET /api/agents; a MEMBER reaches it (200), member-permitted. NOT an operator-tier route.
        "GET /api/server-now",
        // CYP-286: the WS-ticket mint is READ-TIER by construction (gated by `requireCommReader`, which admits a
        // verified MEMBER session by design). It mints a short-lived, single-use ticket bound to the caller's OWN
        // resolved read-subject (carol's identityId — she cannot mint for another subject), consumed only by the
        // read sockets' `wsReaderOrNull` → the SAME ACL-filtered, fail-closed-empty read access her Kratos cookie
        // already grants. NO escalation (alternate transport for the identity she has), NO write path (the send +
        // `/ws/agent` gates don't consume tickets). So a MEMBER reaches it (201), member-permitted — exactly the
        // CYP-417 shape (own-subject / read-only / no-escalation), NOT an operator-tier route.
        "POST /api/ws-ticket",
        // CYP-462: the connector CATALOG is a READ-TIER (PARTICIPANT) fidelity preview — 100% static, secret-free,
        // tenant-free connector vocabulary (kinds + declared capabilities), the same authenticated read line as
        // GET /api/agents/server-now. A MEMBER reaches it (200), member-permitted; the connector *mutation*
        // (POST /api/agents/{id}/connector) stays operator-only. NOT an operator-tier route.
        "GET /api/connectors",
        // CYP-326 — the compact-orchestration STATUS read is read-tier (token OR verified human session), like the
        // config reads: a MEMBER reaches it (200), content-free. The operator write POST /api/compact/config stays denied.
        "GET /api/compact/status",
        // CYP-232 — avatar serve + preset preview are READ-TIER (token OR verified human session), so the tokenless
        // SPA renders avatars; a MEMBER reaches them (200/404, not tier-denied) exactly like the other reads above.
        "GET /api/agents/{id}/avatar", "GET /api/agents/{id}/avatar/preview",
        // CYP-320 — the by-id edit-prefill detail + the LIVE-CLAUDE.md read are READ-TIER (token OR verified human
        // session), the SAME posture as the roster list / avatar reads: the token-only `requireParticipant` 401'd a
        // browser on its Kratos session cookie (dead edit-panel fields). A MEMBER reaches them (200/404, not
        // tier-denied); the operator-gated HARD-overwrite `POST /api/agents/{id}/claude-md` stays in the deny set.
        "GET /api/agents/{id}", "GET /api/agents/{id}/claude-md",
        // CYP-242/244 — the channel-share GET is READ-TIER (token OR verified session) but per-channel
        // canRead-SCOPED (CYP-244): a reader gets 200, a non-reader a uniform 403. It is NOT a flat operator-tier
        // route, so it belongs on the allowlist — the matrix's tier deny-set is not the right net for it (the
        // canRead scoping is proven directly by ChannelShareRoutesTest.cyp244_*). On this walk's synthetic
        // (non-existent) channel id neither role is a reader, so both 403 — no tier leak either way.
        "GET /api/channels/{id}/share",
        // CYP-188 P2b-iii — message SEND is no longer operator-tier: the gate admits a session, and the per-channel
        // `canWrite` at postAsAgent is the authz (deny-without-grant 403 / allow-with-grant 201). It is ACL-gated,
        // not tier-denied → member-permitted here; the deny/allow invariant is proven by HumanSendAclTest.
        "POST /api/channels/{id}/messages",
    )

    @Test
    fun memberSession_is403_onEveryOperatorRouteTheOperatorReaches() = testApplication {
        val db = Files.createTempFile("member-403", ".db")
        val store = SqliteRoleStore(db, bootstrapOperatorId = "alice") // CYP-196: alice is the pinned OPERATOR
        val authDeps = AuthDeps(
            tokens = TokenRegistry(emptyMap(), operatorToken = "tok-op"),
            idp = FakeIdentityProvider(
                mapOf(
                    "sess-alice" to ResolvedIdentity("alice", verified = true),
                    "sess-carol" to ResolvedIdentity("carol", verified = true),
                ),
            ),
            roles = store, nowMs = { 1_000L },
        )
        lateinit var app: Application
        application {
            app = this
            installPlatform(bootFake(), authDeps, settingsClient = KratosSettingsClient("http://localhost:1"))
        }
        startApplication()

        // Bootstrap the tiers via the public whoami: alice is the PINNED OPERATOR (CYP-196); carol → MEMBER.
        suspend fun ApplicationTestBuilder.bump(session: String) =
            client.request("/api/auth/me") { header("X-Session-Token", session) }
        bump("sess-alice"); bump("sess-carol")

        val endpoints = enumerate(app.routing { }).filter { it.path.startsWith("/api") }
        assertTrue(endpoints.size >= 20, "route enumeration found too few /api endpoints (${endpoints.size})")

        var operatorRoutesChecked = 0
        val leaks = mutableListOf<String>()
        for (ep in endpoints) {
            // CYP-234a-2b: normalize the additive /api/v1 alias to /api so BOTH prefixes are held to the
            // identical MEMBER allowlist / operator-deny matrix (a mis-gate under either prefix is caught).
            val key = "${ep.method} ${ep.path.replace("/api/v1/", "/api/")}"
            if (key in memberAllowlist) continue
            val concrete = ep.path.replace(Regex("\\{[^}]*}"), "metatest-x")
            val m = HttpMethod.parse(ep.method)
            // Did the OPERATOR human pass the guard? (401/403 = a participant/token route her session can't use.)
            val opStatus = client.request(concrete) { method = m; header("X-Session-Token", "sess-alice") }.status
            if (opStatus == HttpStatusCode.Unauthorized || opStatus == HttpStatusCode.Forbidden) continue
            // This is an OPERATOR-session route → the MEMBER must be authz-denied 403, never 2xx.
            operatorRoutesChecked++
            val memStatus = client.request(concrete) { method = m; header("X-Session-Token", "sess-carol") }.status
            if (memStatus != HttpStatusCode.Forbidden) leaks += "$key → MEMBER got $memStatus (expected 403)"
        }
        assertTrue(operatorRoutesChecked >= 8, "too few operator routes exercised ($operatorRoutesChecked) — walk/bootstrap broken?")
        assertTrue(leaks.isEmpty(), "MEMBER was NOT 403 on operator routes (leak/mis-gate): $leaks")

        store.close(); Files.deleteIfExists(db)
    }

    // ---- routing-tree enumeration (mirrors ProtectedRouteEnumerationTest) ----

    private data class Endpoint(val method: String, val path: String)

    private fun enumerate(root: RoutingNode): List<Endpoint> {
        val out = mutableListOf<Endpoint>()
        fun visit(node: RoutingNode) {
            (node.selector as? HttpMethodRouteSelector)?.let { out += Endpoint(it.method.value, pathOf(node)) }
            node.children.forEach { visit(it) }
        }
        visit(root)
        return out
    }

    private fun pathOf(node: RoutingNode): String {
        val segments = ArrayDeque<String>()
        var cur: RoutingNode? = node
        while (cur != null) {
            when (val s = cur.selector) {
                is PathSegmentConstantRouteSelector -> segments.addFirst(s.value)
                is PathSegmentParameterRouteSelector -> segments.addFirst("{${s.name}}")
                else -> {}
            }
            cur = cur.parent
        }
        return "/" + segments.joinToString("/")
    }

    // ---- fake boot (mirrors ProtectedRouteEnumerationTest.bootFake) ----

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
        val gitRoot = Files.createTempDirectory("member403-test").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> FakeProcess() }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
    }
}
