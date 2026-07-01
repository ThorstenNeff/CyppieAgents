package com.tneff.cyppieagents.auth

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AuthMe
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
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
) : AuthRepository {

    private val platform = platformBaseUrl.trimEnd('/')
    private val kratos = kratosBaseUrl.trimEnd('/')

    /**
     * The recovery flow [requestReset] opened. Kratos recovery here is **code-only + flow-scoped**: the
     * emailed 6-digit code is valid only for the flow it was issued against, so [setNewPassword] must submit
     * it to THIS held flow, not a freshly-initialised one. Null → fall back to a fresh flow (hermetic path).
     */
    private var recoveryFlowId: String? = null

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
            // Hold the recovery flow id: the emailed code is scoped to THIS flow, so setNewPassword submits
            // it to the same flow (Kratos recovery here is code-only, flow-scoped — no deep-link).
            val flow = initFlow("recovery")
            recoveryFlowId = flow.id.ifBlank { null }
            val action = flow.action.ifBlank { "$kratos/self-service/recovery?flow=${flow.id}" }
            val resp = client.post(action) {
                authHeaders()
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("method", "code"); put("email", email) }.toString())
            }
            if (resp.status.value == 429) ResetRequestResult.RateLimited(retryAfterOf(resp)) else ResetRequestResult.Accepted
        }

    // --- §7.5 set-new-password (recovery deep-link → settings). Reconciled at the live gate. ---

    override suspend fun setNewPassword(token: String, newPassword: String): SetPasswordResult =
        failClosed(SetPasswordResult.TokenInvalid) {
            // Step 1: complete the recovery flow with the emailed code → yields a privileged settings session.
            // Submit to the HELD flow the code is scoped to (requestReset opened it); fall back to a fresh
            // flow only when there is none (hermetic / link-carried context).
            val heldFlow = recoveryFlowId
            val recovery = if (heldFlow != null) {
                client.post("$kratos/self-service/recovery?flow=$heldFlow") {
                    authHeaders()
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("method", "code"); put("code", token) }.toString())
                }
            } else {
                submitFlow("recovery", buildJsonObject { put("method", "code"); put("code", token) })
            }
            if (recovery.status.value == 429) return@failClosed SetPasswordResult.RateLimited(retryAfterOf(recovery))
            if (!recovery.status.isSuccess()) return@failClosed SetPasswordResult.TokenInvalid
            captureSession(recovery.bodyAsText())
            // Step 2: set the new password on the settings flow the recovery established.
            val settings = submitFlow(
                "settings",
                buildJsonObject {
                    put("method", "password")
                    put("password", newPassword)
                },
            )
            when {
                settings.status.isSuccess() -> SetPasswordResult.Ok
                settings.status.value == 429 -> SetPasswordResult.RateLimited(retryAfterOf(settings))
                else -> SetPasswordResult.TokenInvalid
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

    /** Init a native flow (`GET /self-service/{kind}/api`) then POST the [payload] to its `ui.action`. */
    private suspend fun submitFlow(kind: String, payload: JsonObject): HttpResponse {
        val flow = initFlow(kind)
        val action = flow.action.ifBlank { "$kratos/self-service/$kind?flow=${flow.id}" }
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
