package com.tneff.cyppieagents.auth

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AuthMe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * CYP-182 — hermetic end-to-end for the live [HttpAuthRepository] against an **embedded Ktor** faking the
 * P3 contract: the platform whoami `GET /api/auth/me` (`:core AuthMe`), the Kratos self-service flows via
 * the same-origin proxy (`/.ory/kratos/public/self-service/{kind}/{api|submit}`), and Kratos `sessions/whoami`.
 * Same harness style as the CYP-123 connector real-swap E2E (embedded Netty on port 0 → repo points at it).
 * This proves the repo drives the flows + maps every response branch per contract; the LIVE Kratos/Caddy
 * end-to-end is a later gate (PO 2026-07-01).
 */
class HttpAuthRepositoryE2eTest {

    private data class SubmitResp(val status: Int, val body: String, val retryAfter: String? = null)

    private class Fixture {
        var me: AuthMe = AuthMe(authenticated = false)
        var whoami: String = """{"identity":{"traits":{"email":"boot@example.com"}}}"""
        var onSubmit: (kind: String, body: String) -> SubmitResp =
            { _, _ -> SubmitResp(200, """{"session_token":"tok-123"}""") }
        var port: Int = 0
        var lastSubmit: Pair<String, String>? = null
    }

    private fun withFixture(
        configure: Fixture.() -> Unit = {},
        block: suspend (Fixture, HttpAuthRepository, InMemoryAuthSessionStore) -> Unit,
    ) = runBlocking {
        val fx = Fixture().apply(configure)
        val server = embeddedServer(Netty, port = 0) {
            routing {
                get("/api/auth/me") {
                    call.respondText(CommJson.encodeToString(AuthMe.serializer(), fx.me), ContentType.Application.Json)
                }
                get("/.ory/kratos/public/sessions/whoami") {
                    call.respondText(fx.whoami, ContentType.Application.Json)
                }
                get("/.ory/kratos/public/self-service/{kind}/api") {
                    val kind = call.parameters["kind"]
                    call.respondText(
                        """{"id":"f1","ui":{"action":"http://127.0.0.1:${fx.port}/.ory/kratos/public/self-service/$kind"}}""",
                        ContentType.Application.Json,
                    )
                }
                post("/.ory/kratos/public/self-service/logout/api") {
                    call.respondText("{}", ContentType.Application.Json)
                }
                post("/.ory/kratos/public/self-service/{kind}") {
                    val kind = call.parameters["kind"]!!
                    val body = call.receiveText()
                    fx.lastSubmit = kind to body
                    val r = fx.onSubmit(kind, body)
                    if (r.retryAfter != null) call.response.headers.append(HttpHeaders.RetryAfter, r.retryAfter)
                    call.respondText(r.body, ContentType.Application.Json, HttpStatusCode.fromValue(r.status))
                }
            }
        }
        server.start(wait = false)
        fx.port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO)
        try {
            val store = InMemoryAuthSessionStore()
            val repo = HttpAuthRepository(client, "http://127.0.0.1:${fx.port}", sessionStore = store)
            block(fx, repo, store)
        } finally {
            client.close()
            server.stop(100, 200)
        }
    }

    // --- session() / whoami ---

    @Test
    fun session_notAuthenticated_mapsNone() = withFixture({ me = AuthMe(authenticated = false) }) { _, repo, _ ->
        assertEquals(SessionState.None, repo.session())
    }

    @Test
    fun session_verified_mapsVerified() =
        withFixture({ me = AuthMe(authenticated = true, role = "MEMBER", verified = true) }) { _, repo, _ ->
            assertEquals(SessionState.Verified, repo.session())
        }

    @Test
    fun session_unverified_mapsUnverified_withSelfReflectingEmail() = withFixture({
        me = AuthMe(authenticated = true, role = null, verified = false)
        whoami = """{"identity":{"traits":{"email":"pending@example.com"}}}"""
    }) { _, repo, _ ->
        val s = repo.session()
        assertIs<SessionState.Unverified>(s)
        assertEquals("pending@example.com", s.email) // boot echo from Kratos whoami (no platform PII)
    }

    // --- login ---

    @Test
    fun login_success_capturesSessionToken_andMapsVerified() = withFixture({
        me = AuthMe(authenticated = true, role = "MEMBER", verified = true)
        onSubmit = { _, _ -> SubmitResp(200, """{"session_token":"live-tok"}""") }
    }) { _, repo, store ->
        assertEquals(LoginResult.Verified, repo.login("user@example.com", "hunter2"))
        assertEquals("live-tok", store.sessionToken()) // native token captured + will be replayed as X-Session-Token
    }

    @Test
    fun login_unverified_mapsUnverifiedWithTypedEmail() = withFixture({
        me = AuthMe(authenticated = true, role = null, verified = false)
    }) { _, repo, _ ->
        val r = repo.login("typed@example.com", "hunter2")
        assertIs<LoginResult.Unverified>(r)
        assertEquals("typed@example.com", r.email)
    }

    @Test
    fun login_badCredentials_mapsGenericRejected() = withFixture({
        onSubmit = { _, _ -> SubmitResp(400, """{"ui":{"messages":[{"id":4000006,"text":"credentials invalid","type":"error"}]}}""") }
    }) { _, repo, _ ->
        assertEquals(LoginResult.Rejected, repo.login("user@example.com", "wrong")) // generic, no enumeration
    }

    @Test
    fun login_throttled_mapsHonest429_withRetryAfter() = withFixture({
        onSubmit = { _, _ -> SubmitResp(429, """{"error":{"code":"rate_limited","message":"slow down"}}""", retryAfter = "30") }
    }) { _, repo, _ ->
        val r = repo.login("user@example.com", "hunter2")
        assertIs<LoginResult.RateLimited>(r)
        assertEquals("30", r.retryAfter)
    }

    // --- register (neutral) ---

    @Test
    fun register_success_mapsNeutralPending() = withFixture({
        onSubmit = { _, _ -> SubmitResp(200, """{"session_token":"reg-tok"}""") }
    }) { _, repo, _ ->
        val r = repo.register("new@example.com", "hunter2")
        assertIs<RegisterResult.Pending>(r)
        assertEquals("new@example.com", r.email)
    }

    @Test
    fun register_throttled_mapsRateLimited() = withFixture({
        onSubmit = { _, _ -> SubmitResp(429, "{}", retryAfter = "60") }
    }) { _, repo, _ ->
        val r = repo.register("new@example.com", "hunter2")
        assertIs<RegisterResult.RateLimited>(r)
        assertEquals("60", r.retryAfter)
    }

    // --- recovery request (always neutral) ---

    @Test
    fun requestReset_serverError_stillNeutralAccepted() = withFixture({
        onSubmit = { _, _ -> SubmitResp(400, """{"ui":{"messages":[{"text":"no such account"}]}}""") }
    }) { _, repo, _ ->
        // Enumeration-safe: even a 4xx maps to the neutral Accepted — never leaks account existence.
        assertEquals(ResetRequestResult.Accepted, repo.requestReset("maybe@example.com"))
    }

    @Test
    fun requestReset_throttled_mapsRateLimited() = withFixture({
        onSubmit = { _, _ -> SubmitResp(429, "{}") }
    }) { _, repo, _ ->
        assertIs<ResetRequestResult.RateLimited>(repo.requestReset("user@example.com"))
    }

    // --- verify deep-link ---

    @Test
    fun verifyEmail_ok() = withFixture({ onSubmit = { _, _ -> SubmitResp(200, "{}") } }) { _, repo, _ ->
        assertEquals(VerifyResult.Ok, repo.verifyEmail("verify-code"))
    }

    @Test
    fun verifyEmail_invalidToken() = withFixture({ onSubmit = { _, _ -> SubmitResp(410, "{}") } }) { _, repo, _ ->
        assertEquals(VerifyResult.TokenInvalid, repo.verifyEmail("stale-code"))
    }

    // --- reset deep-link (recovery → settings) ---

    @Test
    fun setNewPassword_recoveryThenSettings_ok() = withFixture({
        onSubmit = { kind, _ -> SubmitResp(200, if (kind == "recovery") "{}" else "{}") }
    }) { _, repo, _ ->
        assertEquals(SetPasswordResult.Ok, repo.setNewPassword("recovery-code", "brandNewPw"))
    }

    @Test
    fun setNewPassword_invalidRecoveryCode_mapsTokenInvalid() = withFixture({
        onSubmit = { kind, _ -> if (kind == "recovery") SubmitResp(400, "{}") else SubmitResp(200, "{}") }
    }) { _, repo, _ ->
        assertEquals(SetPasswordResult.TokenInvalid, repo.setNewPassword("stale-code", "brandNewPw"))
    }

    // --- logout ---

    @Test
    fun logout_clearsSessionToken() = withFixture { _, repo, store ->
        store.setSessionToken("live-tok")
        repo.logout()
        assertEquals(null, store.sessionToken())
    }
}
