package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.FakeIdentityProvider
import com.tneff.cyppieagents.auth.InMemoryRoleStore
import com.tneff.cyppieagents.auth.ResolvedIdentity
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Role
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Desktop-Hub E2E — **launch → login → hub HANDOFF** (Team-2, Post-Login-Lane).
 *
 * The seam PO1 named: **session → authorized-reads.** After the desktop logs in via GitHub-OIDC, the
 * resulting **verified operator session** must reach the hub; a launch that never established a session must
 * not. Proven through the REAL embedded platform + the REAL server auth path.
 *
 * "Real, not stub": the login FIX (CYP-576) is client-side — the native OIDC flow exchanges the callback for
 * a **Kratos session token** the native app sends as `X-Session-Token`. This drives exactly that server path:
 * `X-Session-Token` → `IdentityProvider.resolve` → verified → `RoleStore` → OPERATOR → hub. The `idp`/`RoleStore`
 * are hermetic fakes (a live GitHub→Kratos round-trip needs Kratos — that is the **live-stack tooth**, run at
 * the dogfood deploy), but the SESSION→AUTHORIZED-READS seam under test is the production one, not a token stub.
 *
 * Complementary, not duplicative: Team-1-Tester owns login/OIDC up to session establishment; this is the other
 * side (an established session reaching authorized reads). Scoped to the HANDOFF — one verified-operator-session
 * -reaches-hub positive + one no-session fail-closed negative. It does NOT re-derive the 401/403 tier matrix or
 * the verified-vs-unverified gate (`AuthGuardTest` / `MemberTierGuardTest` own those). Non-vacuity is the
 * authed-reaches / unauth-refused PAIR through the real auth path; the guard's own mutation coverage lives there.
 */
class DesktopLoginHandoffE2eTest {

    private val operatorIdentity = "op-human"
    private val operatorSessionToken = "kratos-session-op-human" // stands in for CYP-576's exchanged session token

    private fun projects() =
        listOf(SeedProject("default", "Default", listOf(SeedAgent("po", Role.PO), SeedAgent("backend"))))

    /** AuthDeps wired to the REAL native-session path: a verified session token → the pinned OPERATOR identity. */
    private fun E2ePlatformAuthDeps() = AuthDeps(
        tokens = com.tneff.cyppieagents.routing.TokenRegistry(emptyMap(), operatorToken = null),
        idp = FakeIdentityProvider(mapOf(operatorSessionToken to ResolvedIdentity(operatorIdentity, verified = true))),
        roles = InMemoryRoleStore(bootstrapOperatorId = operatorIdentity),
        nowMs = { 0L },
    )

    @Test
    fun verifiedOperatorSession_reachesTheHubRoster_theLoginHandoff() = runBlocking {
        e2ePlatform(projects(), authDeps = E2ePlatformAuthDeps()).use { p ->
            // The post-login native session token (X-Session-Token) → verified OPERATOR → lands on the hub surface.
            val roster: List<Agent> = p.client(token = null).use {
                it.get("${p.baseUrl}/api/agents") { header("X-Session-Token", operatorSessionToken) }.body()
            }
            assertTrue(roster.any { it.id == "po" } && roster.any { it.id == "backend" },
                "a verified operator session reaches the hub roster after login (the session→authorized-reads handoff lands)")
        }
    }

    @Test
    fun unauthenticatedLaunch_isRefused_theHandoffRequiresAnEstablishedSession() = runBlocking {
        e2ePlatform(projects(), authDeps = E2ePlatformAuthDeps()).use { p ->
            // No session token (and no bearer) → no hub access, fail-closed. ONE guard check making the positive
            // handoff non-vacuous; the full tier / verified-gate matrix is AuthGuardTest/MemberTierGuardTest's.
            val res: HttpResponse = p.client(token = null).use { it.get("${p.baseUrl}/api/agents") }
            assertEquals(HttpStatusCode.Unauthorized, res.status,
                "no established session → no hub access (the handoff requires a logged-in session)")
        }
    }
}
