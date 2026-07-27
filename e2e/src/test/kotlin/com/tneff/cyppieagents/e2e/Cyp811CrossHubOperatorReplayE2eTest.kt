package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.FakeIdentityProvider
import com.tneff.cyppieagents.auth.InMemoryRoleStore
import com.tneff.cyppieagents.auth.ResolvedIdentity
import com.tneff.cyppieagents.model.AuthMe
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.TokenRegistry
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * §9.5/§9.6 (CYP-811 loopback multi-hub closure) — **Leg B-AUTH: the cross-hub operator-cookie replay is refused
 * at the AUTHORIZATION tier**, the server-side half of the distinct-loopback-IP closure. QA coverage-hardening
 * slice F4-Leg-B (complements CYP-818's structural unit teeth; this is the behavioral, real-boot e2e).
 *
 * **The threat (§9.5):** two hubs a human runs on one box. On DISTINCT loopback IPs (127.0.0.1 vs 127.0.0.2) the
 * browser keeps SEPARATE cookie jars, so Hub-A's operator session is never auto-sent to Hub-B — but that jar
 * partition is a BROWSER property, not server-observable. The question this pins: if an operator session minted
 * against Hub-A is *manually replayed* to Hub-B, does the server confer operator authority? It must NOT.
 *
 * **Fidelity (the load-bearing bit — else this is by-construction vacuous):** production hubs on a box share ONE
 * Kratos (`CYPPIE_KRATOS_URL`, forwarded via the gateway) but each has its OWN per-hub `SqliteRoleStore`
 * (`PlatformWiring.kt:445`, per-hub `bootstrapOperatorId`). So this test uses a **SHARED [FakeIdentityProvider]**
 * (one fake Kratos: opA's session is verified+AAL2 and cryptographically valid at BOTH hubs) and **DISTINCT
 * per-hub role stores** (Hub-A pins opA as its bootstrap OPERATOR; Hub-B pins a DIFFERENT operator, so opA is a
 * stranger). Distinct fake IdPs would reject opA's token at Hub-B on *authentication* — proving nothing about the
 * real threat, where the session IS valid and the ONLY server-side discriminator is the per-hub RoleStore.
 *
 * Non-vacuity is the PAIR: opA IS the OPERATOR at its home hub (positive control) AND opA's SAME session is
 * honored as a verified MEMBER at the foreign hub (so the operator-route refusal is authZ, not "unknown session").
 * Both hubs model a loopback deploy (`browserOperatorPostureEnabled = true`) so the AAL2 cookie→OPERATOR posture
 * is adequate and the refusal is purely the per-hub authority boundary, not the S-AAL2b posture gate.
 *
 * Scope: this is the AUTHORIZATION half. The LOCK half (same-IP boot-reject / distinct-IP both-boot via the
 * CYP-818 B-2 network-namespace sentinel) is a separate slice against Backend's B-2 seam.
 */
class Cyp811CrossHubOperatorReplayE2eTest {

    private val opASession = "kratos-session-opA"
    private val opA = "op-A"
    private val opB = "op-B"

    /** ONE shared fake Kratos: opA's session resolves to a verified, AAL2 identity at EVERY hub that uses it. */
    private val sharedIdp = FakeIdentityProvider(mapOf(opASession to ResolvedIdentity(opA, verified = true, aal2 = true)))

    private fun projects() =
        listOf(SeedProject("default", "Default", listOf(SeedAgent("po", Role.PO), SeedAgent("backend"))))

    /** A loopback hub sharing [sharedIdp] but with its OWN authorization store pinning [bootstrapOperator]. */
    private fun hubAuth(bootstrapOperator: String) = AuthDeps(
        tokens = TokenRegistry(emptyMap(), operatorToken = null), // session axis only; no machine-operator bearer
        idp = sharedIdp,
        roles = InMemoryRoleStore(bootstrapOperatorId = bootstrapOperator),
        nowMs = { 0L },
        browserOperatorPostureEnabled = true, // loopback deploy → cookie→OPERATOR posture adequate (S-AAL2b)
    )

    @Test
    fun operatorSessionFromHubA_confersNoOperatorAuthorityAtHubB_theCrossHubReplayRefused() = runBlocking {
        e2ePlatform(projects(), authDeps = hubAuth(bootstrapOperator = opA)).use { hubA ->
            e2ePlatform(projects(), authDeps = hubAuth(bootstrapOperator = opB)).use { hubB ->
                // ── Positive control (non-vacuity): opA IS the OPERATOR at its home hub → the operator-only roster
                //    is granted. Without this, the 403 at Hub-B could be for any reason (bad session, wrong header,
                //    an unmounted route) and would prove nothing.
                hubA.client().use { c ->
                    val r: HttpResponse = c.get("${hubA.baseUrl}/api/workspace/members") { header("X-Session-Token", opASession) }
                    assertEquals(HttpStatusCode.OK, r.status,
                        "positive control: opA is the pinned OPERATOR at its home hub → the operator-only roster is granted")
                }

                // ── AuthN honored at Hub-B (non-vacuity for the tier): the SAME session is a valid, verified identity
                //    at Hub-B (shared Kratos) → a MEMBER-tier read succeeds. So the refusal below is AUTHORIZATION
                //    (role), not AUTHENTICATION (unknown session) — the distinction that makes this a real proof.
                hubB.client().use { c ->
                    val r: HttpResponse = c.get("${hubB.baseUrl}/api/events") { header("X-Session-Token", opASession) }
                    assertEquals(HttpStatusCode.OK, r.status,
                        "opA's SAME session is honored as a verified MEMBER at the foreign hub (member read 200) — the identity travels; only operator authority must not")
                }

                // ── ★ THE CLOSURE: the operator session minted against Hub-A confers NO operator authority at Hub-B.
                //    Operator authority is per-hub (the RoleStore boundary); a replayed cross-hub operator cookie is
                //    only a MEMBER → the operator-only roster is refused 403, even though the session is valid at B.
                hubB.client().use { c ->
                    val r: HttpResponse = c.get("${hubB.baseUrl}/api/workspace/members") { header("X-Session-Token", opASession) }
                    assertEquals(HttpStatusCode.Forbidden, r.status,
                        "★ cross-hub replay refused: Hub-A's operator session is only a MEMBER at Hub-B → operator-only roster 403 (operator authority does NOT travel across hubs, despite the shared Kratos)")
                }
            }
        }
    }

    @Test
    fun authMeRoleOracle_reportsOperatorAtHome_memberAtForeignHub_verifiedAtBoth() = runBlocking {
        e2ePlatform(projects(), authDeps = hubAuth(bootstrapOperator = opA)).use { hubA ->
            e2ePlatform(projects(), authDeps = hubAuth(bootstrapOperator = opB)).use { hubB ->
                // The public /api/auth/me whoami reports the RESOLVED role directly — the per-hub authority boundary
                // shown without hitting a guarded route: same session, OPERATOR at home, only MEMBER at the foreigner.
                val meA: AuthMe = hubA.client().use { it.get("${hubA.baseUrl}/api/auth/me") { header("X-Session-Token", opASession) }.body() }
                val meB: AuthMe = hubB.client().use { it.get("${hubB.baseUrl}/api/auth/me") { header("X-Session-Token", opASession) }.body() }

                assertEquals("OPERATOR", meA.role, "the same session resolves OPERATOR at its home hub")
                assertEquals("MEMBER", meB.role, "the same session resolves only MEMBER at the foreign hub — the per-hub authority boundary, directly")
                // verified at BOTH: the boundary is AUTHORIZATION, not identity (a distinct-IdP fixture would fail this).
                assertEquals(true, meA.verified, "verified at the home hub (shared Kratos)")
                assertEquals(true, meB.verified, "verified at the foreign hub too — identity travels, operator authority does not")
            }
        }
    }
}
