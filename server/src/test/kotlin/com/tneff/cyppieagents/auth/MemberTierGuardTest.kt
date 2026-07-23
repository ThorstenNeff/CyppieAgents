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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-186 (S18 kick-off / BE1) — **role wiring + the MEMBER deny-tier**, proven on the guard.
 *
 * (1) **Role wiring, both auth ways:** the role is assigned by `ensureAssigned` on first VERIFIED access
 * (the guard / `/api/auth/me`), independent of HOW the identity verified. A **password** user is verified
 * immediately; an **OIDC** user lands `verified=false` (S2) and gets **no role** until the on-platform
 * verify-then-admit flips it — modeled here by a session whose `verified` flips false→true. First verified =
 * OPERATOR, every later verified = MEMBER.
 * (2) **MEMBER is denied OPERATOR ops (403, authz — not 401 authn):** a MEMBER satisfies a MEMBER gate but is
 * `operator_required`-403 on an OPERATOR gate.
 * (3) **Bootstrap race stays tight:** two verified identities racing first-access → exactly one OPERATOR
 * (the `SqliteRoleStore` RC3 atomicity, exercised through the guard).
 */
class MemberTierGuardTest {

    private val db = Files.createTempFile("member-tier", ".db")

    /** Mount an OPERATOR-gated and a MEMBER-gated route sharing one [AuthDeps] (per-instance guard selector). */
    private fun ApplicationTestBuilder.installTiers(idp: IdentityProvider, store: SqliteRoleStore) {
        val deps = AuthDeps(
            tokens = TokenRegistry(emptyMap(), operatorToken = "tok-op"),
            idp = idp, roles = store, nowMs = { 1_000L },
        
            browserOperatorPostureEnabled = true,
        )
        application {
            install(StatusPages) { exception<ApiException> { call, cause -> call.respond(cause.status) } }
            routing {
                authenticatedApi(deps, AuthRole.OPERATOR) { get("/api/op") { call.respondText("op") } }
                authenticatedApi(deps, AuthRole.MEMBER) { get("/api/mem") { call.respondText("mem") } }
            }
        }
    }

    private suspend fun ApplicationTestBuilder.status(path: String, session: String) =
        client.get(path) { header("X-Session-Token", session) }.status

    @Test
    fun roleWiring_pinnedIsOperator_laterIsMember_oidcVerifyThenAdmit() = testApplication {
        val store = SqliteRoleStore(db, bootstrapOperatorId = "alice") // CYP-196: alice is the PINNED OPERATOR
        val daveVerified = AtomicBoolean(false) // OIDC: verified flips false→true at on-platform verify
        val idp = object : IdentityProvider {
            override suspend fun resolve(credential: SessionCredential?): ResolvedIdentity? = when (credential?.value) {
                "alice" -> ResolvedIdentity("alice", verified = true, aal2 = true)          // password — verified immediately
                "dave" -> ResolvedIdentity("dave", verified = daveVerified.get()) // OIDC — verify-then-admit
                else -> null
            }
        }
        installTiers(idp, store)

        // OIDC dave, still unverified → NO role assigned, denied (RC1 verified-required).
        assertEquals(HttpStatusCode.Unauthorized, status("/api/op", "dave"))
        assertEquals(HttpStatusCode.Unauthorized, status("/api/mem", "dave"))

        // Password alice (the PINNED identity) verifies → OPERATOR.
        assertEquals(HttpStatusCode.OK, status("/api/op", "alice"))

        // OIDC dave completes on-platform verify → assignable now; NOT the pinned id ⇒ dave = MEMBER (no land-grab).
        daveVerified.set(true)
        assertEquals(HttpStatusCode.Forbidden, status("/api/op", "dave"))  // MEMBER on OPERATOR gate → 403 authz
        assertEquals(HttpStatusCode.OK, status("/api/mem", "dave"))        // MEMBER satisfies MEMBER gate

        store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun explicitPin_onlyPinnedBecomesOperator_otherIsMember_evenRacing() = testApplication {
        // CYP-196: with explicit assignment there is no "first-verified" race — ONLY the pinned identity can be
        // OPERATOR. Two verified identities hit the OPERATOR gate concurrently; the pinned one (u1) is OPERATOR
        // (200), the other (u2) is MEMBER (403) — deterministic, and the single slot still holds exactly one.
        val store = SqliteRoleStore(db, bootstrapOperatorId = "u1")
        val idp = object : IdentityProvider {
            override suspend fun resolve(credential: SessionCredential?): ResolvedIdentity? = when (credential?.value) {
                "u1" -> ResolvedIdentity("u1", verified = true, aal2 = true)
                "u2" -> ResolvedIdentity("u2", verified = true)
                else -> null
            }
        }
        installTiers(idp, store)

        val statuses = coroutineScope {
            listOf("u1", "u2").map { async { status("/api/op", it) } }.awaitAll()
        }
        assertEquals(HttpStatusCode.OK, status("/api/op", "u1"), "the pinned identity is OPERATOR")
        assertEquals(HttpStatusCode.Forbidden, status("/api/op", "u2"), "a non-pinned identity is MEMBER (403), never OPERATOR")
        assertEquals(1, statuses.count { it == HttpStatusCode.OK }, "exactly one (the pinned) is OPERATOR")

        store.close(); Files.deleteIfExists(db)
    }
}
