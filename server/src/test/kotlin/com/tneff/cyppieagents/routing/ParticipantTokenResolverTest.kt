package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.FakeIdentityProvider
import com.tneff.cyppieagents.auth.InMemoryRoleStore
import com.tneff.cyppieagents.auth.ParticipantTokenStore
import com.tneff.cyppieagents.auth.authenticatedApi
import com.tneff.cyppieagents.auth.resolveAuthState
import com.tneff.cyppieagents.comm.HubState
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-234b-2 — the tier-resolver-unify: a [ParticipantTokenStore] token resolves as a read-SUBJECT through the
 * SAME participant/read resolvers as a human MEMBER ([requireCommReader]/[requireCommWriter]/[requireParticipant]/
 * [wsReaderOrNull]) — and NEVER satisfies the operator gate. The load-bearing 234b invariant: the token only
 * widens WHO reaches the ACL chokepoints, never the authz (its subject is fail-closed-empty until granted, and
 * it can never reach OPERATOR).
 */
class ParticipantTokenResolverTest {

    private val reg = TokenRegistry(mapOf("tok-agent" to "backend"), "tok-op")
    private val store = ParticipantTokenStore { 1_000L }
    private val raw = store.mint("byo-consumer-1")
    private val deps = AuthDeps(
        tokens = reg,
        idp = FakeIdentityProvider(emptyMap()),
        roles = InMemoryRoleStore(),
        nowMs = { 1_000L },
        participantTokens = store,
    )

    private fun app(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            install(io.ktor.server.plugins.statuspages.StatusPages) {
                exception<ApiException> { call, cause -> call.respondText(text = "", status = cause.status) }
            }
            routing {
                get("/read") { call.respondText(call.requireCommReader(deps, reg)) }
                get("/write") { call.respondText(call.requireCommWriter(deps, reg)) }
                get("/part") { call.respondText(call.requireParticipant(deps)) }
                authenticatedApi(deps, AuthRole.OPERATOR) { get("/op") { call.respondText("ok") } }
                // the MEMBER-tier gate (like GET /api/events) — the ①-fix leak surface
                authenticatedApi(deps, AuthRole.MEMBER) { get("/member") { call.respondText("member-ok") } }
                // the 2nd path: /api/auth/me whoami
                get("/whoami") {
                    val me = call.resolveAuthState(deps)
                    call.respondText(if (me.authenticated) "auth:${me.role}" else "anon")
                }
            }
        }
        block(client)
    }

    private fun bearer(t: String) = "Bearer $t"

    @Test
    fun participantToken_resolvesToItsNamespacedSubject_throughReadResolvers_andRejectedAtWrite() = app { client ->
        // CYP-297 Layer 1: the READ resolvers admit the token but resolve it to the RESERVED `participant:`
        // principal — NOT the bare subject, and never the operator id → its canRead/canWrite is a distinct
        // fail-closed-empty row that inherits no foreign grant.
        for (path in listOf("/read", "/part")) {
            val resp = client.get(path) { header("Authorization", bearer(raw)) }
            assertEquals(HttpStatusCode.OK, resp.status, "$path admits the participant token")
            assertEquals("participant:byo-consumer-1", resp.bodyAsText(), "$path resolves it to its NAMESPACED read-subject")
            assertTrue(resp.bodyAsText() != HubState.OPERATOR_ID, "$path must NOT resolve a participant token to the operator")
        }
        // CYP-297 Layer 3: the WRITE resolver (requireCommWriter) rejects a participant token outright — read-tier
        // never writes, even before the ACL chokepoint.
        assertEquals(HttpStatusCode.Forbidden, client.get("/write") { header("Authorization", bearer(raw)) }.status, "/write must reject a participant token (read-only tier)")
    }

    @Test
    fun participantToken_neverSatisfiesTheOperatorGate() = app { client ->
        val resp = client.get("/op") { header("Authorization", bearer(raw)) }
        assertTrue(
            resp.status == HttpStatusCode.Unauthorized || resp.status == HttpStatusCode.Forbidden,
            "a participant token must be DENIED at an operator gate (was ${resp.status}) — it never carries operator authz",
        )
    }

    @Test
    fun noToken_failsClosed() = app { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/read").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/part").status)
    }

    @Test
    fun revokedParticipantToken_failsClosed() = app { client ->
        // resolve works first…
        assertEquals(HttpStatusCode.OK, client.get("/read") { header("Authorization", bearer(raw)) }.status)
        // …then revoke → the SAME token is immediately denied through the resolver.
        store.revoke(raw)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/read") { header("Authorization", bearer(raw)) }.status, "a revoked participant token fails closed at the resolver")
    }

    // ---- CYP-234b-2 ①-fix: bearer validation at resolvePrincipal + resolveAuthState ----

    @Test
    fun participantToken_neverSatisfiesTheMemberGate() = app { client ->
        // THE missing tooth: a participant token reads comm via canRead, but NEVER the MEMBER-tier gate
        // (/api/events). Mutation: revert resolvePrincipal to MachineAgent(agentFor(bearer)) → this reds.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/member") { header("Authorization", bearer(raw)) }.status, "a participant token must NEVER satisfy the MEMBER role gate")
    }

    @Test
    fun garbageBearer_failsClosed_atTheMemberGate_andWhoami() = app { client ->
        val garbage = bearer("garbage-not-a-real-token")
        // ①-fix: an unknown bearer is NOT MEMBER → 401 at the MEMBER gate (was 200 — the leak).
        assertEquals(HttpStatusCode.Unauthorized, client.get("/member") { header("Authorization", garbage) }.status, "a garbage bearer must not satisfy the MEMBER gate")
        // 2nd path: whoami reports anon for a garbage bearer (was authenticated MEMBER).
        assertEquals("anon", client.get("/whoami") { header("Authorization", garbage) }.bodyAsText(), "a garbage bearer is not authenticated at /whoami")
    }

    @Test
    fun knownAgentAndOperatorTokens_keepTheirTiers_noRegress() = app { client ->
        // CYP-186 BE2 regression guard: a KNOWN agent token still reads the MEMBER tier.
        assertEquals(HttpStatusCode.OK, client.get("/member") { header("Authorization", bearer("tok-agent")) }.status, "a known agent token still satisfies MEMBER (CYP-186 BE2)")
        assertEquals("auth:MEMBER", client.get("/whoami") { header("Authorization", bearer("tok-agent")) }.bodyAsText())
        // the operator token still satisfies both MEMBER and OPERATOR.
        assertEquals(HttpStatusCode.OK, client.get("/member") { header("Authorization", bearer("tok-op")) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/op") { header("Authorization", bearer("tok-op")) }.status, "a known operator still satisfies OPERATOR")
        assertEquals("auth:OPERATOR", client.get("/whoami") { header("Authorization", bearer("tok-op")) }.bodyAsText())
    }
}
