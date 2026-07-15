package com.tneff.cyppieagents.auth

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AuthMe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * CYP-182 — hermetic end-to-end for the live [HttpAuthRepository] against an **embedded Ktor** faking the
 * P3 contract: the platform whoami `GET /api/auth/me` (`:core AuthMe`), the Kratos self-service flows via
 * the same-origin proxy (`/.ory/kratos/public/self-service/{kind}/{api|submit}`), and Kratos `sessions/whoami`.
 * Same harness style as the CYP-123 connector real-swap E2E (embedded Netty on port 0 → repo points at it).
 * This proves the repo drives the flows + maps every response branch per contract; the LIVE Kratos/Caddy
 * end-to-end is a later gate (PO 2026-07-01).
 */
class HttpAuthRepositoryE2eTest {

    private data class SubmitResp(val status: Int, val body: String, val retryAfter: String? = null, val setCookies: List<String> = emptyList())

    private class Fixture {
        var me: AuthMe = AuthMe(authenticated = false)
        var whoami: String = """{"identity":{"traits":{"email":"boot@example.com"}}}"""
        var onSubmit: (kind: String, body: String) -> SubmitResp =
            { _, _ -> SubmitResp(200, """{"session_token":"tok-123"}""") }
        var port: Int = 0
        var lastSubmit: Pair<String, String>? = null
        // CYP-187: the platform register-wrapper (`POST /api/auth/register`). Default = the branch-invariant
        // success (`200 {"status":"verification_pending"}`); `lastRegister` records the body the client POSTs
        // there (null ⇒ the wrapper was never hit — e.g. a regression back to the raw Kratos registration flow).
        var registerResp: SubmitResp = SubmitResp(200, """{"status":"verification_pending"}""")
        var lastRegister: String? = null
    }

    private fun withFixture(
        configure: Fixture.() -> Unit = {},
        block: suspend (Fixture, HttpAuthRepository, InMemoryAuthSessionStore) -> Unit,
    ) = runBlocking {
        val fx = Fixture().apply(configure)
        val server = embeddedServer(Netty, port = 0) {
            routing {
                get("/api/auth/me") {
                    // Public content-free whoami: it reflects the caller's CREDENTIAL — no cookie/token → not
                    // authenticated (so a cleared recovery session correctly reads as None).
                    val hasCred = !call.request.cookies["ory_kratos_session"].isNullOrBlank() ||
                        !call.request.header("X-Session-Token").isNullOrBlank()
                    val me = if (hasCred) fx.me else AuthMe(authenticated = false)
                    call.respondText(CommJson.encodeToString(AuthMe.serializer(), me), ContentType.Application.Json)
                }
                get("/.ory/kratos/public/sessions/whoami") {
                    call.respondText(fx.whoami, ContentType.Application.Json)
                }
                // CYP-187 — the platform register-wrapper. The client's ONLY external register path now; it must
                // POST {email,password} HERE, not to the raw Kratos registration self-service flow (MUST-1).
                post("/api/auth/register") {
                    fx.lastRegister = call.receiveText()
                    val r = fx.registerResp
                    if (r.retryAfter != null) call.response.headers.append(HttpHeaders.RetryAfter, r.retryAfter)
                    call.respondText(r.body, ContentType.Application.Json, HttpStatusCode.fromValue(r.status))
                }
                get("/.ory/kratos/public/self-service/{kind}/api") {
                    val kind = call.parameters["kind"]
                    // Cookie-on-API-flow blockade: a recovery ory_kratos_session cookie present at /login/api → 400
                    // (the post-reset re-login must be cookie-free — the recovery session must be cleared first).
                    if (kind == "login" && !call.request.headers["Cookie"].isNullOrBlank()) {
                        call.respondText(
                            """{"error":{"reason":"Cookie present on API-initiated login flow — blocked"}}""",
                            ContentType.Application.Json, HttpStatusCode.BadRequest,
                        )
                        return@get
                    }
                    // CYP-576: the native API-flow login init arms the token-exchange (`return_session_token_exchange_code=true`)
                    // and Kratos returns a `session_token_exchange_code` (the init half) on the flow. Model it so githubStart's
                    // init step yields the init code (else it fail-closes to Error — the stale-test breakage).
                    val wantExchange = call.request.queryParameters["return_session_token_exchange_code"] == "true"
                    val body = if (wantExchange) {
                        """{"id":"f1","session_token_exchange_code":"init-code-123","ui":{"action":"http://127.0.0.1:${fx.port}/.ory/kratos/public/self-service/$kind"}}"""
                    } else {
                        """{"id":"f1","ui":{"action":"http://127.0.0.1:${fx.port}/.ory/kratos/public/self-service/$kind"}}"""
                    }
                    call.respondText(body, ContentType.Application.Json)
                }
                post("/.ory/kratos/public/self-service/logout/api") {
                    call.respondText("{}", ContentType.Application.Json)
                }
                // Browser recovery/settings flows: Kratos renders `flow.action` with its OWN base_url (a
                // different host:port — :4433 in deploy), so FOLLOWING it drops the proxy-bound csrf/session
                // cookies (ktor HttpCookies is port-specific) → 403 security_csrf_violation. Modelled here as
                // an "action-trap" path that 403s. The repo MUST NOT follow `flow.action`; it must reconstruct
                // `$kratos/self-service/{recovery|settings}?flow=<id>` (the generic route below). Following the
                // trap reddens; reconstructing greens — this teeths the same-origin-submit invariant.
                get("/.ory/kratos/public/self-service/recovery/browser") {
                    call.respondText(
                        """{"id":"rfb","ui":{"action":"http://127.0.0.1:${fx.port}/.ory/kratos/public/self-service/recovery/action-trap","nodes":[{"attributes":{"name":"csrf_token","value":"rcsrf","type":"hidden"}}]}}""",
                        ContentType.Application.Json,
                    )
                }
                // Browser login flow init (OIDC uses it): carries the csrf_token node the repo echoes.
                get("/.ory/kratos/public/self-service/login/browser") {
                    call.respondText(
                        """{"id":"lfb","ui":{"nodes":[{"attributes":{"name":"csrf_token","value":"lcsrf","type":"hidden"}}]}}""",
                        ContentType.Application.Json,
                    )
                }
                get("/.ory/kratos/public/self-service/settings/browser") {
                    call.respondText(
                        """{"id":"sf","ui":{"action":"http://127.0.0.1:${fx.port}/.ory/kratos/public/self-service/settings/action-trap","nodes":[{"attributes":{"name":"csrf_token","value":"csrf-abc","type":"hidden"}}]}}""",
                        ContentType.Application.Json,
                    )
                }
                post("/.ory/kratos/public/self-service/recovery/action-trap") {
                    fx.lastSubmit = "actionTrap" to call.receiveText()
                    call.respondText("""{"error":{"id":"security_csrf_violation"}}""", ContentType.Application.Json, HttpStatusCode.Forbidden)
                }
                post("/.ory/kratos/public/self-service/settings/action-trap") {
                    fx.lastSubmit = "actionTrap" to call.receiveText()
                    call.respondText("""{"error":{"id":"security_csrf_violation"}}""", ContentType.Application.Json, HttpStatusCode.Forbidden)
                }
                post("/.ory/kratos/public/self-service/{kind}") {
                    val kind = call.parameters["kind"]!!
                    // Cookie-on-API-flow blockade at SUBMIT too (faithful to Kratos): a lingering recovery
                    // ory_kratos_session cookie on /login is 400 — makes postResetLoginIsCookieFree non-vacuous
                    // (submitFlow swallows the GET-init 400 and still POSTs; that init-status gap is F4/follow-up).
                    if (kind == "login" && !call.request.headers["Cookie"].isNullOrBlank()) {
                        call.respondText(
                            """{"error":{"reason":"Cookie present on API-initiated login flow — blocked"}}""",
                            ContentType.Application.Json, HttpStatusCode.BadRequest,
                        )
                        return@post
                    }
                    val body = call.receiveText()
                    fx.lastSubmit = kind to body
                    val r = fx.onSubmit(kind, body)
                    if (r.retryAfter != null) call.response.headers.append(HttpHeaders.RetryAfter, r.retryAfter)
                    r.setCookies.forEach { call.response.headers.append(HttpHeaders.SetCookie, it) }
                    call.respondText(r.body, ContentType.Application.Json, HttpStatusCode.fromValue(r.status))
                }
            }
        }
        server.start(wait = false)
        fx.port = server.engine.resolvedConnectors().first().port
        // HttpCookies with a SHARED storage: the mock's Set-Cookie is consumed into this jar (as the live
        // client does), sessionEstablished reads the jar, and the repo clears the recovery cookie via it.
        val cookieStorage = AcceptAllCookiesStorage()
        val client = HttpClient(CIO) { install(HttpCookies) { storage = cookieStorage } }
        try {
            val store = InMemoryAuthSessionStore()
            val repo = HttpAuthRepository(
                client, "http://127.0.0.1:${fx.port}", sessionStore = store, cookieStorage = cookieStorage,
            )
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

    /**
     * CYP-413 (S-I) **leak tooth:** a DEFINITIVE session end — `session()`/whoami reports unauthenticated (server
     * logout/expiry/revocation) — must clear the stale local credential so it is never replayed. Non-vacuous: the
     * token is seeded first, so a passing assertion requires the clear to actually run. Mutation-proof: remove the
     * `sessionStore.clear()` in `session()`'s `!authenticated` branch → the token lingers → this reddens.
     */
    @Test
    fun session_serverReportsUnauthenticated_clearsStaleTokenAtSessionEnd() =
        withFixture({ me = AuthMe(authenticated = false) }) { _, repo, store ->
            store.setSessionToken("stale-tok")
            assertEquals("stale-tok", store.sessionToken())
            assertEquals(SessionState.None, repo.session())
            assertNull(store.sessionToken(), "a definitive session end must drop the stale token (leak guard)")
        }

    @Test
    fun session_verified_member_mapsVerifiedMember() =
        withFixture({ me = AuthMe(authenticated = true, role = "MEMBER", verified = true) }) { _, repo, store ->
            store.setSessionToken("sess") // a credential present → /api/auth/me reflects the logged-in state
            assertEquals(SessionState.Verified(UserTier.MEMBER), repo.session())
        }

    @Test
    fun session_verified_operator_mapsVerifiedOperator() =
        withFixture({ me = AuthMe(authenticated = true, role = "OPERATOR", verified = true) }) { _, repo, store ->
            store.setSessionToken("sess")
            assertEquals(SessionState.Verified(UserTier.OPERATOR), repo.session())
        }

    @Test
    fun session_verified_nullOrUnknownRole_failsClosedToMember() =
        withFixture({ me = AuthMe(authenticated = true, role = null, verified = true) }) { _, repo, store ->
            store.setSessionToken("sess")
            // ⭐ CYP-186 fail-closed: an absent/unknown role never grants OPERATOR (mutation → OPERATOR reddens).
            assertEquals(SessionState.Verified(UserTier.MEMBER), repo.session())
        }

    @Test
    fun session_verified_garbageRole_failsClosedToMember() =
        withFixture({ me = AuthMe(authenticated = true, role = "superadmin", verified = true) }) { _, repo, store ->
            store.setSessionToken("sess")
            assertEquals(SessionState.Verified(UserTier.MEMBER), repo.session())
        }

    @Test
    fun session_unverified_mapsUnverified_withSelfReflectingEmail() = withFixture({
        me = AuthMe(authenticated = true, role = null, verified = false)
        whoami = """{"identity":{"traits":{"email":"pending@example.com"}}}"""
    }) { _, repo, store ->
        store.setSessionToken("sess")
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
        assertIs<LoginResult.Verified>(repo.login("user@example.com", "hunter2"))
        assertEquals("live-tok", store.sessionToken()) // native token captured + will be replayed as X-Session-Token
    }

    @Test
    fun currentSessionToken_reflectsCapturedNativeToken_forShellThreading() = withFixture({
        me = AuthMe(authenticated = true, role = "MEMBER", verified = true)
        onSubmit = { _, _ -> SubmitResp(200, """{"session_token":"live-tok"}""") }
    }) { _, repo, _ ->
        // CYP-188: this is the exact value App threads into the shared HTTP/WS client (X-Session-Token). Null before
        // login (no session), the captured native token after — so a session-only user authenticates its reads.
        assertEquals(null, repo.currentSessionToken())
        repo.login("user@example.com", "hunter2")
        assertEquals("live-tok", repo.currentSessionToken())
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

    // --- register → platform wrapper (neutral). CYP-187: POST /api/auth/register, NOT the raw Kratos flow. ---

    @Test
    fun register_success_mapsNeutralPending() = withFixture({
        registerResp = SubmitResp(200, """{"status":"verification_pending"}""")
    }) { fx, repo, _ ->
        val r = repo.register("new@example.com", "hunter2")
        assertIs<RegisterResult.Pending>(r)
        assertEquals("new@example.com", r.email)
        assertEquals(null, fx.lastSubmit) // the raw Kratos registration submit was NOT driven
    }

    @Test
    fun register_hitsWrapperPath_withEmailAndPassword_notRawKratos() = withFixture({
        registerResp = SubmitResp(200, """{"status":"verification_pending"}""")
    }) { fx, repo, _ ->
        // ⭐ Path-teeth (MUST-1): the client POSTs {email,password} to the wrapper. A regression back to
        // submitFlow("registration",…) never hits /api/auth/register → fx.lastRegister stays null → RED.
        repo.register("new@example.com", "hunter2")
        val body = fx.lastRegister
        assertIs<String>(body)
        assertEquals(true, body.contains("\"email\":\"new@example.com\""))
        assertEquals(true, body.contains("\"password\":\"hunter2\""))
        assertEquals(null, fx.lastSubmit) // no raw Kratos self-service submit at all
    }

    @Test
    fun register_throttled_mapsRateLimited() = withFixture({
        registerResp = SubmitResp(429, "{}", retryAfter = "60") // edge throttle (G3) fronts the wrapper
    }) { _, repo, _ ->
        val r = repo.register("new@example.com", "hunter2")
        assertIs<RegisterResult.RateLimited>(r)
        assertEquals("60", r.retryAfter)
    }

    @Test
    fun register_serverError_mapsGenericInvalidInput() = withFixture({
        // 503 = admin/Kratos outage, uniform for everyone (MUST-3). Fail-closed to the generic negative — never a
        // fabricated Pending, never an enumeration branch. (400 invalid_request maps the same way.)
        registerResp = SubmitResp(503, """{"error":"unavailable"}""")
    }) { _, repo, _ ->
        assertIs<RegisterResult.InvalidInput>(repo.register("new@example.com", "hunter2"))
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
        // The CORRECT code answers 422 browser_location_change_required + a COOKIE session (no body token —
        // the v1.3.0 browser-recovery reality) via the PROXY-reconstructed submit (`/self-service/recovery`,
        // the generic route); Step 2 completes on the proxy-reconstructed settings submit. The greening proves
        // the repo reconstructed rather than following `flow.action` (whose action-trap path 403s).
        onSubmit = { kind, _ ->
            when (kind) {
                "recovery" -> SubmitResp(
                    422,
                    """{"error":{"id":"browser_location_change_required"}}""",
                    setCookies = listOf("ory_kratos_session=hermetic-sess; Path=/"),
                )
                "settings" -> SubmitResp(200, "{}")
                else -> SubmitResp(200, "{}")
            }
        }
    }) { _, repo, _ ->
        assertEquals(SetPasswordResult.Ok, repo.setNewPassword("recovery-code", "brandNewPw"))
    }

    @Test
    fun setNewPassword_recovery422_otherErrorId_mapsTokenInvalid() = withFixture({
        // fail-closed: only browser_location_change_required is the accept signal — any other 422 error.id → TokenInvalid.
        onSubmit = { kind, _ ->
            // Even WITH a session cookie, a non-matching error.id is not the accept signal → TokenInvalid.
            if (kind == "recovery") {
                SubmitResp(422, """{"error":{"id":"some_other_continuation"}}""", setCookies = listOf("ory_kratos_session=s; Path=/"))
            } else {
                SubmitResp(200, "{}")
            }
        }
    }) { _, repo, _ ->
        assertEquals(SetPasswordResult.TokenInvalid, repo.setNewPassword("code", "brandNewPw"))
    }

    @Test
    fun setNewPassword_recovery422_correctErrorId_butNoSession_mapsTokenInvalid() = withFixture({
        // fail-closed (reviewer's condition): the error.id string alone is not enough — WITHOUT a real issued
        // session the code is NOT treated as accepted.
        onSubmit = { kind, _ ->
            // Correct error.id but NO session issued (no cookie, no token) → NOT accepted (session is the proof).
            if (kind == "recovery") SubmitResp(422, """{"error":{"id":"browser_location_change_required"}}""")
            else SubmitResp(200, "{}")
        }
    }) { _, repo, _ ->
        assertEquals(SetPasswordResult.TokenInvalid, repo.setNewPassword("code", "brandNewPw"))
    }

    @Test
    fun setNewPassword_recovery422_foreignCookieNoSession_mapsTokenInvalid() = withFixture({
        // MUT-10b teeth: a browser_location_change_required whose ONLY jar cookie is a FOREIGN one (a
        // csrf/flow cookie Kratos really sets) — no ory_kratos_session — is NOT an established session. The
        // name-specific jar check must reject it; an "any cookie" loosening would false-positive here (accept
        // the code + set a password without a real recovery session), so this reddens on that mutation.
        onSubmit = { kind, _ ->
            when (kind) {
                "recovery" -> SubmitResp(
                    422,
                    """{"error":{"id":"browser_location_change_required"}}""",
                    setCookies = listOf("csrf_token=not-a-session; Path=/"),
                )
                "settings" -> SubmitResp(200, "{}") // reachable only if the gate wrongly passed (the mutant)
                else -> SubmitResp(200, "{}")
            }
        }
    }) { _, repo, _ ->
        assertEquals(SetPasswordResult.TokenInvalid, repo.setNewPassword("code", "brandNewPw"))
    }

    @Test
    fun setNewPassword_success_clearsSession_postResetStateIsNone() = withFixture({
        // Security: the recovery session is authorised by the emailed CODE, not the new password → it must NOT
        // linger as an implicit login. `me` is authenticated, so if the recovery cookie were NOT cleared the
        // post-reset session() would wrongly read authenticated (this reddens on the "clear removed" mutation).
        me = AuthMe(authenticated = true, role = "MEMBER", verified = true)
        onSubmit = { kind, _ ->
            when (kind) {
                "recovery" -> SubmitResp(
                    422,
                    """{"error":{"id":"browser_location_change_required"}}""",
                    // Browser recovery sets BOTH the session AND a dynamic csrf cookie — clearRecoverySession
                    // must expire ALL of them, or a lingering csrf still 400s the post-reset /login (MUT-B teeth).
                    setCookies = listOf("ory_kratos_session=recov; Path=/", "csrf_token_9f2a=zzz; Path=/"),
                )
                "settings" -> SubmitResp(200, "{}")
                else -> SubmitResp(200, "{}")
            }
        }
    }) { _, repo, _ ->
        assertEquals(SetPasswordResult.Ok, repo.setNewPassword("code", "newPw"))
        assertEquals(SessionState.None, repo.session()) // cleared → not implicitly logged in
    }

    @Test
    fun setNewPassword_success_clearsSession_postResetLoginIsCookieFree() = withFixture({
        me = AuthMe(authenticated = true, role = null, verified = false) // the fresh re-login → unverified session
        onSubmit = { kind, _ ->
            when (kind) {
                "recovery" -> SubmitResp(
                    422,
                    """{"error":{"id":"browser_location_change_required"}}""",
                    // Browser recovery sets BOTH the session AND a dynamic csrf cookie — clearRecoverySession
                    // must expire ALL of them, or a lingering csrf still 400s the post-reset /login (MUT-B teeth).
                    setCookies = listOf("ory_kratos_session=recov; Path=/", "csrf_token_9f2a=zzz; Path=/"),
                )
                "settings" -> SubmitResp(200, "{}")
                "login" -> SubmitResp(200, """{"session_token":"login-sess"}""")
                else -> SubmitResp(200, "{}")
            }
        }
    }) { _, repo, _ ->
        assertEquals(SetPasswordResult.Ok, repo.setNewPassword("code", "newPw"))
        // Re-login with the new password must be cookie-free: /login/api 400s if the recovery cookie lingers.
        assertIs<LoginResult.Unverified>(repo.login("user@example.com", "newPw"))
    }

    @Test
    fun setNewPassword_failure_doesNotClearSession() = withFixture({
        onSubmit = { kind, _ -> if (kind == "recovery") SubmitResp(403, "{}") else SubmitResp(200, "{}") }
    }) { _, repo, store ->
        store.setSessionToken("pre-existing")
        assertEquals(SetPasswordResult.TokenInvalid, repo.setNewPassword("wrong-code", "newPw"))
        // Clear happens ONLY on the success path — a failed reset must not touch existing state.
        assertEquals("pre-existing", store.sessionToken())
    }

    @Test
    fun setNewPassword_invalidRecoveryCode_mapsTokenInvalid() = withFixture({
        // Grounded matrix: a WRONG code answers 403 → the fail-closed teeth (never a silent success).
        onSubmit = { kind, _ -> if (kind == "recovery") SubmitResp(403, "{}") else SubmitResp(200, "{}") }
    }) { _, repo, _ ->
        assertEquals(SetPasswordResult.TokenInvalid, repo.setNewPassword("stale-code", "brandNewPw"))
    }

    // --- logout ---

    // --- GitHub OIDC start (§5) ---

    @Test
    fun githubStart_redirect_returnsRedirectUrl() = withFixture({
        // The login flow's oidc/github submit answers with a browser-location-change to GitHub.
        onSubmit = { kind, _ ->
            if (kind == "login") {
                SubmitResp(422, """{"error":{"id":"browser_location_change_required"},"redirect_browser_to":"https://github.test/login/oauth/authorize?state=abc"}""")
            } else {
                SubmitResp(200, "{}")
            }
        }
    }) { _, repo, _ ->
        val r = repo.githubStart()
        assertIs<GithubStart.Redirect>(r)
        assertEquals("https://github.test/login/oauth/authorize?state=abc", r.url)
        // CYP-576: the native API-flow also carries the init half (session_token_exchange_code) for the token-exchange.
        assertEquals("init-code-123", r.initCode)
    }

    @Test
    fun githubStart_noRedirect_returnsError() = withFixture({
        onSubmit = { kind, _ -> if (kind == "login") SubmitResp(400, """{"ui":{"messages":[{"text":"nope"}]}}""") else SubmitResp(200, "{}") }
    }) { _, repo, _ ->
        assertEquals(GithubStart.Error, repo.githubStart())
    }

    @Test
    fun logout_clearsSessionToken() = withFixture { _, repo, store ->
        store.setSessionToken("live-tok")
        repo.logout()
        assertEquals(null, store.sessionToken())
    }
}
