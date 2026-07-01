package com.tneff.cyppieagents.auth

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AuthMe
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.cookies
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.Cookie
import io.ktor.http.Url
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Live [AuthRepository] for the login gate (CYP-182 / P3) — the **real-swap** of the CYP-177
 * [StubAuthRepository], with **no VM/UI change** (the seam's whole point; the CYP-91/CYP-123 real-swap
 * pattern). It binds to two surfaces, per the P3 contract (`backend/plans/CYP-176-P3-client-auth-contract-sketch.md`):
 *
 *  1. **Auth state** — the platform's content-free whoami `GET /api/auth/me` → `:core [AuthMe]`
 *     `{authenticated, role?, verified}` drives [session] (the ONE clean read; PO 2026-07-01). The
 *     unverified-boot email echo is read self-reflectingly from Kratos `sessions/whoami` (no platform PII).
 *  2. **Identity flows** — login / registration / recovery / verification are **Kratos self-service
 *     flows** driven through the same-origin proxy (`{kratosBaseUrl}/self-service/{flow}/api`, CYP-181 P2.2).
 *     The MVP maps the seam's fixed email/password to the Kratos **password method** (PO Q3); `ui.nodes`
 *     rendering is deferred (P4).
 *
 * **Credentials:** a native target stores the login `session_token` in [sessionStore] and replays it as
 * `X-Session-Token`; a browser target rides the `ory_kratos_session` cookie automatically (same-origin), so
 * the store simply stays empty there. **Honesty contract (mapped, never leaked):** login rejects
 * **generically** ([LoginResult.Rejected]); register/recovery answer **neutrally**; a 429 is surfaced honestly
 * ([retryAfterOf]), never a fake success; deep-link tokens are passed to Kratos, never rendered/logged.
 *
 * **Fail-closed:** any transport/parse error or unexpected status resolves to the safe negative
 * (`None`/`Rejected`/`InvalidInput`/`TokenInvalid`), never a fabricated "authenticated". The deep-link
 * flows ([setNewPassword]/[verifyEmail]) model the documented Kratos code-method shape; the live end-to-end
 * path is reconciled at the post-P2.2-Caddy gate (PO: live e2e is a later gate).
 */
class HttpAuthRepository(
    private val client: HttpClient,
    private val platformBaseUrl: String,
    private val kratosBaseUrl: String = "$platformBaseUrl/.ory/kratos/public",
    private val sessionStore: AuthSessionStore = InMemoryAuthSessionStore(),
    /**
     * The client's cookie jar storage (the SAME instance installed in its `HttpCookies`) — needed to
     * **clear** the browser `ory_kratos_session` after a password reset (the elevated recovery session must
     * not linger; §setNewPassword). Null → only the native token is cleared (browser targets pass it).
     */
    private val cookieStorage: CookiesStorage? = null,
) : AuthRepository {

    private val platform = platformBaseUrl.trimEnd('/')
    private val kratos = kratosBaseUrl.trimEnd('/')

    /**
     * The **browser** recovery flow [requestReset] opened — its submit `action` and `csrf_token`. Kratos
     * recovery here is code-only + flow-scoped, and must run in **browser** mode: only browser-recovery
     * establishes the `ory_kratos_session` cookie (API-recovery-422 issues no session), and browser flows
     * require the csrf token. [setNewPassword] submits the code to THIS held action. Null → fall back to a
     * fresh browser flow (hermetic path without a prior requestReset).
     */
    private var recoveryAction: String? = null
    private var recoveryCsrf: String? = null

    /** Extract a query param value (`?name=…` / `&name=…`) from a URL/query string, or null. */
    private fun queryParam(s: String, name: String): String? =
        Regex("[?&]" + Regex.escape(name) + "=([^&\\s\"]+)").find(s)?.groupValues?.get(1)

    // --- §7.1 boot probe: /api/auth/me (public, content-free) → AuthState ---

    override suspend fun session(): SessionState = failClosed(SessionState.None) {
        val me = authMe()
        when {
            !me.authenticated -> SessionState.None
            me.verified -> SessionState.Verified
            else -> SessionState.Unverified(whoamiEmailOrBlank()) // unverified: self-reflecting email echo
        }
    }

    private suspend fun authMe(): AuthMe {
        val resp = client.get("$platform/api/auth/me") { authHeaders() }
        // The endpoint is deliberately public + content-free (200 with {authenticated:false} when not logged
        // in, never 401). A non-2xx is unexpected → fail-closed to "not authenticated".
        if (!resp.status.isSuccess()) return AuthMe(authenticated = false)
        return CommJson.decodeFromString(AuthMe.serializer(), resp.bodyAsText())
    }

    /** The caller's OWN email from Kratos whoami (self-reflecting, no platform PII). Blank if unavailable. */
    private suspend fun whoamiEmailOrBlank(): String = runCatching {
        val resp = client.get("$kratos/sessions/whoami") { authHeaders() }
        if (!resp.status.isSuccess()) return@runCatching ""
        parseKratosWhoamiEmail(resp.bodyAsText()) ?: ""
    }.getOrElse { e -> if (e is CancellationException) throw e; "" }

    // --- §7.2 login ---

    override suspend fun login(email: String, password: String): LoginResult = failClosed(LoginResult.Rejected) {
        val resp = submitFlow(
            "login",
            buildJsonObject {
                put("method", "password")
                put("identifier", email)
                put("password", password)
            },
        )
        when {
            resp.status.isSuccess() -> {
                captureSession(resp.bodyAsText())
                // Kratos login succeeds regardless of verification; the PLATFORM gates on verified (RC1) —
                // so ask /api/auth/me for the verified state. Use the typed email (better than whoami here).
                when (session()) {
                    SessionState.Verified -> LoginResult.Verified
                    is SessionState.Unverified -> LoginResult.Unverified(email)
                    SessionState.None -> LoginResult.Rejected // unexpected post-login → fail-closed
                }
            }
            resp.status.value == 429 -> LoginResult.RateLimited(retryAfterOf(resp))
            else -> LoginResult.Rejected // 400/401 bad creds → generic, no enumeration
        }
    }

    // --- §7.3 register (neutral) ---

    override suspend fun register(email: String, password: String): RegisterResult =
        failClosed(RegisterResult.InvalidInput) {
            val resp = submitFlow(
                "registration",
                buildJsonObject {
                    put("method", "password")
                    put("password", password)
                    put("traits", buildJsonObject { put("email", email) })
                },
            )
            when {
                resp.status.isSuccess() -> {
                    captureSession(resp.bodyAsText())
                    RegisterResult.Pending(email) // neutral: verification pending, never "exists"
                }
                resp.status.value == 429 -> RegisterResult.RateLimited(retryAfterOf(resp))
                else -> RegisterResult.InvalidInput // generic; UI shows the generic register error
            }
        }

    // --- §7.4 recovery request (ALWAYS neutral) ---

    override suspend fun requestReset(email: String): ResetRequestResult =
        failClosed(ResetRequestResult.Accepted) { // neutral even on failure — no enumeration leak
            // Open a BROWSER recovery flow (only browser-recovery establishes the ory_kratos_session cookie —
            // API-recovery issues no session) and HOLD its action + csrf; setNewPassword submits the emailed
            // code to the same flow. Browser flows require the csrf token from the flow init.
            val (action, csrf) = initRecoveryBrowserFlow()
            recoveryAction = action
            recoveryCsrf = csrf
            val resp = client.post(action) {
                authHeaders()
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("method", "code"); put("email", email); if (csrf != null) put("csrf_token", csrf)
                    }.toString(),
                )
            }
            if (resp.status.value == 429) ResetRequestResult.RateLimited(retryAfterOf(resp)) else ResetRequestResult.Accepted
        }

    /** Open a Kratos **browser** recovery flow; return its submit `action` + the `csrf_token` to echo. */
    private suspend fun initRecoveryBrowserFlow(): Pair<String, String?> {
        val resp = client.get("$kratos/self-service/recovery/browser") { authHeaders() }
        val body = resp.bodyAsText()
        val flow = parseKratosFlow(body)
        // Always submit via the configured proxy (`$kratos`) + flow id — NEVER follow the flow's own
        // `action`, which Kratos renders with its OWN base_url (a different host:port). The csrf +
        // ory_kratos_session cookies are bound to the proxy host, and ktor HttpCookies is port-specific, so
        // following `action` to another host drops them → 403 security_csrf_violation. Same-origin invariant.
        val action = "$kratos/self-service/recovery?flow=${flow.id}"
        return action to parseKratosCsrfToken(body)
    }

    // --- §7.5 set-new-password (recovery deep-link → settings). Reconciled at the live gate. ---

    override suspend fun setNewPassword(token: String, newPassword: String): SetPasswordResult =
        failClosed(SetPasswordResult.TokenInvalid) {
            // Step 1: complete the BROWSER recovery flow with the emailed code → establishes the privileged
            // session. Submit to the HELD browser action (requestReset opened it); fall back to a fresh
            // browser flow when there is none (hermetic path without a prior requestReset).
            val (recoveryActionUrl, csrf) = recoveryAction?.let { it to recoveryCsrf } ?: initRecoveryBrowserFlow()
            val recovery = client.post(recoveryActionUrl) {
                authHeaders()
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("method", "code"); put("code", token); if (csrf != null) put("csrf_token", csrf)
                    }.toString(),
                )
            }
            if (recovery.status.value == 429) return@failClosed SetPasswordResult.RateLimited(retryAfterOf(recovery))
            val recoveryBody = recovery.bodyAsText()
            // A CORRECT code in the browser flow answers 422 `browser_location_change_required` (+ an issued
            // session) — the "code accepted → go to settings" success path, NOT a failure. Accept ONLY when
            // BOTH the browser-flow success signal AND a REAL established session are present (the error.id
            // string alone is not enough — Kratos can emit it for other continuations). Every other case
            // (wrong code 403, other/absent error.id, no session, any other 4xx) is fail-closed → TokenInvalid.
            if (!isBrowserFlowSuccess(recovery.status.value, recoveryBody) || !sessionEstablished(recoveryBody)) {
                return@failClosed SetPasswordResult.TokenInvalid
            }
            captureSession(recoveryBody) // native session_token if present; browser rides the ory_kratos_session cookie
            // Step 2: set the new password on the settings flow — **source-aware**, following the mode of the
            // session Step 1 actually established (like the whoami credential-source principle): a native
            // token → `/settings/api` (X-Session-Token carries it); a cookie session (the v1.3.0 browser-
            // recovery-422 case — no token in the body) → `/settings/browser` + csrf_token (the auto-sent
            // recovery cookie would otherwise 400-block an api-initiated flow). Handles both modes, not one.
            val settings = if (parseKratosSessionToken(recoveryBody) != null) {
                submitFlow(
                    "settings",
                    buildJsonObject { put("method", "password"); put("password", newPassword) },
                )
            } else {
                val init = client.get("$kratos/self-service/settings/browser") { authHeaders() }
                val initBody = init.bodyAsText()
                val flow = parseKratosFlow(initBody)
                val csrf = parseKratosCsrfToken(initBody)
                // Same-origin invariant (see initRecoveryBrowserFlow): submit via the proxy + flow id, never flow.action.
                client.post("$kratos/self-service/settings?flow=${flow.id}") {
                    authHeaders()
                    contentType(ContentType.Application.Json)
                    setBody(
                        buildJsonObject {
                            put("method", "password")
                            put("password", newPassword)
                            if (csrf != null) put("csrf_token", csrf)
                        }.toString(),
                    )
                }
            }
            val settingsBody = settings.bodyAsText()
            when {
                settings.status.value == 429 -> SetPasswordResult.RateLimited(retryAfterOf(settings))
                isBrowserFlowSuccess(settings.status.value, settingsBody) -> {
                    clearRecoverySession() // success ONLY: terminate the elevated recovery session (no implicit login)
                    SetPasswordResult.Ok
                }
                else -> SetPasswordResult.TokenInvalid
            }
        }

    /**
     * Terminate the elevated recovery session **after a successful password change** (Option a, security
     * hygiene): the recovery session was authorised by the emailed CODE, not the new password, so it must not
     * linger as an implicit login (least-privilege / re-auth). Drops the native token AND expires the browser
     * `ory_kratos_session` cookie in the jar, so the post-reset state is logged-out and a subsequent `/login`
     * is cookie-free (a recovery cookie on the api login flow would 400). Called ONLY on the success path.
     */
    private suspend fun clearRecoverySession() {
        sessionStore.clear()
        recoveryAction = null
        recoveryCsrf = null
        runCatching {
            val storage = cookieStorage ?: return@runCatching
            val url = Url(kratos)
            // Expire EVERY cookie the recovery flow left on the Kratos host — not only ory_kratos_session but
            // also the dynamic csrf_token_<hash> cookies — so the post-reset /login/api is TRULY cookie-free
            // (Kratos 400s an API-initiated flow that carries ANY cookie, not just the session).
            client.cookies(kratos).forEach { c ->
                storage.addCookie(url, Cookie(name = c.name, value = "", maxAge = 0, path = c.path ?: "/"))
            }
        }
    }

    // --- §7.6 email verification (verify deep-link). Reconciled at the live gate. ---

    override suspend fun verifyEmail(token: String): VerifyResult = failClosed(VerifyResult.TokenInvalid) {
        // The verification deep-link carries `code` + `flow`; submit the code to THAT exact flow. A bare
        // token (no `flow=`) falls back to a fresh flow (hermetic / code-only contexts).
        val flowId = queryParam(token, "flow")
        val code = queryParam(token, "code") ?: token
        val resp = if (flowId != null) {
            client.post("$kratos/self-service/verification?flow=$flowId") {
                authHeaders()
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("method", "code"); put("code", code) }.toString())
            }
        } else {
            submitFlow("verification", buildJsonObject { put("method", "code"); put("code", code) })
        }
        if (resp.status.isSuccess()) VerifyResult.Ok else VerifyResult.TokenInvalid
    }

    // --- §7.7 resend verification (neutral) ---

    override suspend fun resendVerification(): ResendResult = failClosed(ResendResult.Accepted) {
        val email = whoamiEmailOrBlank()
        val resp = submitFlow(
            "verification",
            buildJsonObject {
                put("method", "code")
                put("email", email)
            },
        )
        if (resp.status.value == 429) ResendResult.RateLimited(retryAfterOf(resp)) else ResendResult.Accepted
    }

    // --- §7.8 logout ---

    override suspend fun logout() {
        // Best-effort Kratos native logout; the local session token is dropped regardless (fail-closed exit).
        runCatching {
            val tok = sessionStore.sessionToken()
            if (tok != null) {
                client.post("$kratos/self-service/logout/api") {
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("session_token", tok) }.toString())
                }
            }
        }
        sessionStore.clear()
    }

    // --- Kratos flow driving ---

    /** Init a native flow (`GET /self-service/{kind}/api`) then POST the [payload] via the proxy + flow id. */
    private suspend fun submitFlow(kind: String, payload: JsonObject): HttpResponse {
        val flow = initFlow(kind)
        // Same-origin invariant: submit via the configured proxy + flow id, never the flow's own action host.
        val action = "$kratos/self-service/$kind?flow=${flow.id}"
        return client.post(action) {
            authHeaders()
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
    }

    private suspend fun initFlow(kind: String): KratosFlowRef {
        val resp = client.get("$kratos/self-service/$kind/api") { authHeaders() }
        return parseKratosFlow(resp.bodyAsText())
    }

    /** Store the native `session_token` (if the body carried one). Browser flows set a cookie instead → no-op. */
    private fun captureSession(body: String) {
        parseKratosSessionToken(body)?.let { sessionStore.setSessionToken(it) }
    }

    // --- request helpers ---

    private fun HttpRequestBuilder.authHeaders() {
        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        // Native session credential; browser cookies ride automatically on the same-origin proxy.
        sessionStore.sessionToken()?.let { header("X-Session-Token", it) }
    }

    /** The honest 429 hint from the `Retry-After` header, or null (auth-spec §5.4 — never a fake "sent"). */
    private fun retryAfterOf(resp: HttpResponse): String? =
        resp.headers[HttpHeaders.RetryAfter]?.ifBlank { null }

    /**
     * A Kratos browser-flow **success** for a flow step: a 2xx, OR the `422 browser_location_change_required`
     * "step accepted → continue" signal. NOT sufficient alone to conclude a recovery code was correct — pair
     * it with [sessionEstablished] (the reviewer's condition: the error.id string can appear for other
     * continuations; the real proof is an issued privileged session).
     */
    private fun isBrowserFlowSuccess(status: Int, body: String): Boolean =
        status in 200..299 || (status == 422 && parseKratosErrorId(body) == "browser_location_change_required")

    /**
     * True iff a Kratos session was actually issued — a native `session_token` in [body], OR an
     * `ory_kratos_session` cookie **in the client jar**. The `HttpCookies` plugin CONSUMES the `Set-Cookie`
     * response header into the jar (so the header is empty by the time we'd read it) — read the JAR. Check
     * specifically the `ory_kratos_session` name with a non-blank value (not "any cookie") — a non-session
     * cookie must never be mistaken for an established session on this secret path (fail-closed).
     */
    private suspend fun sessionEstablished(body: String): Boolean {
        if (parseKratosSessionToken(body) != null) return true
        return runCatching {
            client.cookies(kratos).any { it.name == "ory_kratos_session" && it.value.isNotBlank() }
        }.getOrElse { false }
    }

    /** Run [block], mapping any thrown error (except cancellation) to the safe [fallback] (fail-closed). */
    private inline fun <T> failClosed(fallback: T, block: () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            fallback
        }
}
