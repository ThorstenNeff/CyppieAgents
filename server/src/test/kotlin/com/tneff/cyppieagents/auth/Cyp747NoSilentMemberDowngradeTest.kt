package com.tneff.cyppieagents.auth

import com.tneff.cyppieagents.routing.ApiException
import com.tneff.cyppieagents.routing.TokenRegistry
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-747 S-AAL2a-ii — the AAL2 chokepoint's **"no silent MEMBER downgrade" is pinned on a MEMBER/read route**,
 * not only on the operator route. (QA coverage-hardening slice F3.)
 *
 * `Principal.kt:148` denies an AAL1 (password-only) operator by returning `null` — the WHOLE principal, so the
 * caller is 401 EVERYWHERE, deliberately NOT downgraded to the MEMBER access the operator identity would otherwise
 * carry (comment: "an explicit re-auth-to-AAL2, NEVER a silent MEMBER downgrade"). `AuthGuardTest` proves the
 * deny only on the OPERATOR route, where a MEMBER-downgrade and a hard-deny look identical (both non-operator →
 * the operator route refuses either way). The invariant only becomes VISIBLE on a **MEMBER-tier** route: a real
 * downgrade would grant it (200), a hard fail-closed refuses it (401). This nails that difference so a future
 * refactor that let an AAL1 operator "fall through to MEMBER" reddens instead of silently widening read access.
 *
 * Non-vacuity is the trio on the SAME member route: a verified MEMBER is admitted (200 — the route is genuinely
 * open to members, so the AAL1 401 is the downgrade-refusal, not a closed route), an AAL2 operator is admitted
 * (200 — operator ≥ member; it is specifically the AAL1 factor, not the identity, that is refused), and the AAL1
 * operator is refused (401).
 */
class Cyp747NoSilentMemberDowngradeTest {

    private val operator = "olivia" // the pinned bootstrap OPERATOR identity
    private val member = "mallory"  // a verified, non-operator identity

    private val db = Files.createTempFile("no-downgrade-roles", ".db")
    private fun roles() = SqliteRoleStore(db, bootstrapOperatorId = operator)

    /** Mount a MEMBER-tier read route (the surface where a silent downgrade would be VISIBLE as a wrongful 200). */
    private fun ApplicationTestBuilder.installMemberRoute() {
        val deps = AuthDeps(
            tokens = TokenRegistry(emptyMap(), operatorToken = "tok-op"),
            idp = FakeIdentityProvider(
                mapOf(
                    "sess-op-aal2" to ResolvedIdentity(operator, verified = true, aal2 = true),
                    "sess-op-aal1" to ResolvedIdentity(operator, verified = true, aal2 = false), // SAME operator, password-only
                    "sess-member" to ResolvedIdentity(member, verified = true), // a real MEMBER (aal2 irrelevant for MEMBER)
                ),
            ),
            roles = roles(),
            nowMs = { 1_000L },
            browserOperatorPostureEnabled = true, // loopback deploy → the AAL2 operator posture is adequate
        )
        application {
            install(StatusPages) {
                exception<ApiException> { call, cause -> call.respond(cause.status) } // assert the code, not a body
            }
            routing { authenticatedApi(deps, AuthRole.MEMBER) { get("/api/member-read") { call.respondText("ok") } } }
        }
    }

    @Test
    fun aal1Operator_onMemberRoute_is401_notSilentlyDowngradedToMember() = testApplication {
        installMemberRoute()
        // The AAL1 operator session: verified identity, OPERATOR role, but AAL1 → resolvePrincipal returns null. On a
        // MEMBER route that null is a 401, NOT the 200 a silent MEMBER downgrade would produce. THIS is the invariant.
        val res = client.get("/api/member-read") { header("X-Session-Token", "sess-op-aal1") }
        assertEquals(HttpStatusCode.Unauthorized, res.status,
            "an AAL1 operator is fail-closed (401) on a MEMBER route — NOT silently downgraded to MEMBER read access")
    }

    @Test
    fun verifiedMember_onMemberRoute_is200_theRouteGenuinelyAdmitsMembers() = testApplication {
        installMemberRoute()
        // Non-vacuity: the member route really is open to members, so the AAL1 401 above is the downgrade-refusal,
        // not a route that happens to be closed to everyone.
        val res = client.get("/api/member-read") { header("X-Session-Token", "sess-member") }
        assertEquals(HttpStatusCode.OK, res.status, "a verified MEMBER is admitted to the MEMBER route (200)")
    }

    @Test
    fun aal2Operator_onMemberRoute_is200_itIsTheFactorNotTheIdentityThatIsRefused() = testApplication {
        installMemberRoute()
        // The SAME operator identity, but AAL2-backed, is admitted (operator ≥ member) — so the AAL1 refusal is about
        // the missing second factor, not about who the identity is.
        val res = client.get("/api/member-read") { header("X-Session-Token", "sess-op-aal2") }
        assertEquals(HttpStatusCode.OK, res.status, "an AAL2 operator is admitted to the MEMBER route (operator ≥ member)")
    }
}
