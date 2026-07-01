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
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-181 / P2.3 — the verified-flip → P1-guard linkage (the one real platform integration in P2). Recovery
 * changes nothing here; **verification** is the event that flips an identity's `verified` to true, and the P1
 * guard requires `verified==true` (RC1). This proves the transition end-to-end for the SAME identity: an
 * unverified session is 401 on a MEMBER route, and after the verified flip the SAME session is admitted — no
 * new integration, the P1 seam already gates on the field Kratos's verification flow flips.
 */
class P2VerifiedFlipGuardTest {

    /** A session whose identity's `verified` status can be flipped between requests (models the verify event). */
    private class ToggleIdp(@Volatile var verified: Boolean) : IdentityProvider {
        override suspend fun resolve(sessionCredential: String?): ResolvedIdentity? =
            sessionCredential?.let { ResolvedIdentity("member-identity", verified) }
    }

    @Test
    fun unverifiedSessionIs401_thenVerifiedFlipAdmitsSameSession() = testApplication {
        val db = Files.createTempFile("p2-flip-roles", ".db")
        val idp = ToggleIdp(verified = false)
        val deps = AuthDeps(TokenRegistry(emptyMap(), operatorToken = "tok-op"), idp, SqliteRoleStore(db), { 1L })
        application {
            install(StatusPages) { exception<ApiException> { call, cause -> call.respond(cause.status) } }
            routing { authenticatedApi(deps, AuthRole.MEMBER) { get("/api/member/thing") { call.respondText("ok") } } }
        }

        // Before verification: a valid-but-UNVERIFIED session is unauthenticated (RC1 verified-required).
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/member/thing") { header("X-Session-Token", "sess-member") }.status,
        )

        // The verification event flips `verified` → the SAME session is now admitted (MEMBER tier).
        idp.verified = true
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/member/thing") { header("X-Session-Token", "sess-member") }.status,
        )

        Files.deleteIfExists(db)
    }
}
