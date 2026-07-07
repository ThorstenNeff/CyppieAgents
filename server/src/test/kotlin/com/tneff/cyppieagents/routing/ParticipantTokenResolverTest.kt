package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.FakeIdentityProvider
import com.tneff.cyppieagents.auth.InMemoryRoleStore
import com.tneff.cyppieagents.auth.ParticipantTokenStore
import com.tneff.cyppieagents.auth.authenticatedApi
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
            }
        }
        block(client)
    }

    private fun bearer(t: String) = "Bearer $t"

    @Test
    fun participantToken_resolvesToItsSubject_throughEveryReadResolver() = app { client ->
        for (path in listOf("/read", "/write", "/part")) {
            val resp = client.get(path) { header("Authorization", bearer(raw)) }
            assertEquals(HttpStatusCode.OK, resp.status, "$path admits the participant token")
            assertEquals("byo-consumer-1", resp.bodyAsText(), "$path resolves it to its read-SUBJECT")
            // it is a PLAIN subject, NOT the operator id → the ACL (canRead/canWrite) is the only authz.
            assertTrue(resp.bodyAsText() != HubState.OPERATOR_ID, "$path must NOT resolve a participant token to the operator")
        }
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
}
