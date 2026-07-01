package com.tneff.cyppieagents.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CYP-182 — **LIVE** end-to-end for [HttpAuthRepository] against the RUNNING platform-auth stack
 * (server + Kratos v1.3.0 + Caddy same-origin proxy). The real-path counterpart of the hermetic
 * [HttpAuthRepositoryE2eTest]: same repo, same branches, but against **real Kratos** — it proves the wire
 * shapes, the native `X-Session-Token` capture+replay, and the live `sessions/whoami` boot-echo on the real
 * chain. This is the **P3 real-path gate**; the PO-gated stub→real default flip depends on its GO.
 *
 * ## Verification-first posture (deploy-grounded 2026-07-01)
 * Kratos v1.3.0 (reference config) is **verification-first**: **registration issues NO session**
 * (`continue_with=[show_verification_ui]`) — deliberately, so an unverified user is never auto-logged-in
 * (the Auftraggeber's "verify first" posture). But Kratos **does allow login of a not-yet-verified
 * identity**, which yields a session with `verified:false` → the client's [SessionState.Unverified]
 * (`AuthedUnverified`). The `verified:false` guard-deny on protected mutations stays orthogonal (server-side).
 *
 * ## Gated + no-op by default
 * Without `CYPPIE_AUTH_E2E=1` every test returns immediately, so the normal `:app:shared:jvmTest` gate
 * stays green with **no stack** (this file only needs to compile there). The tester runs it with the stack up:
 * ```
 *   CYPPIE_AUTH_E2E=1 \
 *   CYPPIE_AUTH_ORIGIN=http://127.0.0.1:8787 \
 *   CYPPIE_AUTH_PROXY=http://127.0.0.1:8088/.ory/kratos/public \
 *   CYPPIE_AUTH_EMAIL=rc2-test@cyppie.dev \
 *   CYPPIE_AUTH_PASSWORD=<the verified identity's password> \
 *   ./gradlew :app:shared:jvmTest --tests '*HttpAuthRepositoryLiveE2eTest*'
 * ```
 *
 * ## Coverage (target 6/6)
 * `session()` unauth → None · **register** a fresh identity → Pending + **no session** (verification-first) →
 * `session()` still None · **login the unverified identity** → AuthedUnverified with the live whoami echo ·
 * **login the pre-verified** [CYPPIE_AUTH_EMAIL] → Verified (skipped without CYPPIE_AUTH_PASSWORD) · **recovery
 * request** → neutral Accepted · **logout** an active session → None.
 *
 * NOT automated: the **deep-link completion** (`setNewPassword`/`verifyEmail` with the emailed code) — it
 * needs a mailbox to read the code; the init/neutral halves are covered live, the completion mapping stays
 * hermetic. A follow-up can read the code from deploy's Mailpit (`:8025`) once a sample message is confirmed.
 */
class HttpAuthRepositoryLiveE2eTest {

    private val enabled = System.getenv("CYPPIE_AUTH_E2E") == "1"
    private val origin = System.getenv("CYPPIE_AUTH_ORIGIN") ?: "http://127.0.0.1:8787"
    private val proxy = System.getenv("CYPPIE_AUTH_PROXY") ?: "http://127.0.0.1:8088/.ory/kratos/public"
    private val verifiedEmail = System.getenv("CYPPIE_AUTH_EMAIL") ?: "rc2-test@cyppie.dev"
    private val verifiedPassword: String? = System.getenv("CYPPIE_AUTH_PASSWORD")

    private val strongPassword = "Cyppie-Live-E2E-9x!"

    private fun uniqueEmail() = "live-${UUID.randomUUID().toString().substring(0, 8)}@cyppie.dev"

    /** A fresh client+repo+store. HttpCookies so the browser-cookie path also works if the stack sets one. */
    private fun live(block: suspend (HttpAuthRepository, InMemoryAuthSessionStore) -> Unit) {
        if (!enabled) return // no-op without the stack → the normal gate stays green
        runBlocking {
            val client = HttpClient(CIO) { install(HttpCookies) }
            val store = InMemoryAuthSessionStore()
            val repo = HttpAuthRepository(client, origin, kratosBaseUrl = proxy, sessionStore = store)
            try {
                block(repo, store)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun live_session_unauthenticated_mapsNone() = live { repo, _ ->
        assertEquals(SessionState.None, repo.session()) // /api/auth/me is public → {authenticated:false}
    }

    @Test
    fun live_register_verificationFirst_issuesNoSession() = live { repo, store ->
        val email = uniqueEmail()
        assertIs<RegisterResult.Pending>(repo.register(email, strongPassword)) // neutral pending
        // Verification-first: registration issues NO session (continue_with=[show_verification_ui]) —
        // no session element captured, and the user is NOT auto-logged-in.
        assertEquals(null, store.sessionToken())
        assertEquals(SessionState.None, repo.session())
    }

    @Test
    fun live_login_unverifiedIdentity_mapsAuthedUnverified_withWhoamiEcho() = live { repo, store ->
        val email = uniqueEmail()
        repo.register(email, strongPassword)              // verification-first: no session yet
        val login = repo.login(email, strongPassword)     // v1.3.0 ALLOWS login of an unverified identity
        assertIs<LoginResult.Unverified>(login)
        assertEquals(email, login.email)
        assertTrue(store.sessionToken() != null, "an unverified login issues a session_token")
        // /api/auth/me → authenticated:true, verified:false → AuthedUnverified (session active, not verified;
        // the guard-deny on protected mutations is orthogonal, server-side).
        val s = repo.session()
        assertIs<SessionState.Unverified>(s)
        assertEquals(email, s.email) // LIVE self-reflecting whoami echo (no platform PII)
    }

    @Test
    fun live_login_verifiedIdentity_mapsVerified() = live { repo, _ ->
        val pw = verifiedPassword ?: return@live // needs the pre-verified identity's password
        assertEquals(LoginResult.Verified, repo.login(verifiedEmail, pw))
        assertEquals(SessionState.Verified, repo.session())
    }

    @Test
    fun live_requestReset_neutralAccepted() = live { repo, _ ->
        // Enumeration-safe: a real Kratos recovery init answers neutrally whether or not the account exists.
        assertEquals(ResetRequestResult.Accepted, repo.requestReset(uniqueEmail()))
    }

    @Test
    fun live_logout_afterActiveLogin_clearsSession() = live { repo, store ->
        val email = uniqueEmail()
        repo.register(email, strongPassword)
        repo.login(email, strongPassword) // an active (unverified) session
        assertTrue(store.sessionToken() != null)
        repo.logout()
        assertEquals(null, store.sessionToken())
        assertEquals(SessionState.None, repo.session())
    }

    // --- Deep-link completion (recovery-code / verify-code) — reads the code from Mailpit ---
    //
    // Secret hygiene (P2.5): the code/token is read into memory only, NEVER logged and never placed in an
    // assertion/error message — only the repo outcome (Ok/TokenInvalid/Verified) is asserted. The fresh live
    // code is generated per-run and never leaves the box.

    private val mailpit = System.getenv("CYPPIE_MAILPIT") ?: "http://127.0.0.1:8025"
    private val resetPassword = "Cyppie-Reset-New-7z!"

    /** Poll Mailpit for the newest message to [email] whose subject contains [subjectNeedle]; return its Text. */
    private suspend fun mailText(client: HttpClient, email: String, subjectNeedle: String): String {
        repeat(20) {
            val list = client.get("$mailpit/api/v1/search") {
                parameter("query", "to:$email")
                parameter("limit", "10")
            }.bodyAsText()
            val id = KratosJson.parseToJsonElement(list).jsonObject["messages"]?.jsonArray
                ?.firstOrNull { (it.jsonObject["subject"]?.jsonPrimitive?.content ?: "").contains(subjectNeedle, ignoreCase = true) }
                ?.jsonObject?.get("id")?.jsonPrimitive?.content
            if (id != null) {
                val msg = client.get("$mailpit/api/v1/message/$id").bodyAsText()
                return KratosJson.parseToJsonElement(msg).jsonObject["Text"]?.jsonPrimitive?.content ?: ""
            }
            delay(500)
        }
        error("no Mailpit mail to $email matching subject '$subjectNeedle'") // no secret in this message
    }

    /** The bare 6-digit recovery code (its own line in the mail). The text is never logged (it holds the code). */
    private fun recoveryCode(text: String): String =
        Regex("(?m)^\\s*(\\d{6,8})\\s*$").find(text)?.groupValues?.get(1)
            ?: error("recovery code not found in the recovery mail")

    /** The verification link (`…/self-service/verification?code=…&flow=…`); repo parses code+flow from it. */
    private fun verificationLink(text: String): String =
        Regex("https?://\\S*?/self-service/verification\\?\\S+").find(text)?.value?.replace("&amp;", "&")
            ?: error("verification link not found in the verification mail")

    @Test
    fun live_recovery_setNewPassword_ok_thenNewPasswordLogsIn() = live { repo, _ ->
        val mailClient = HttpClient(CIO)
        try {
            val email = uniqueEmail()
            repo.register(email, strongPassword)                                  // create the identity
            assertEquals(ResetRequestResult.Accepted, repo.requestReset(email))   // opens + holds the recovery flow
            val code = recoveryCode(mailText(mailClient, email, "Recover access")) // masked: never logged
            assertEquals(SetPasswordResult.Ok, repo.setNewPassword(code, resetPassword))
            // Sharpened #1: the NEW password actually logs in (a session is issued).
            val relogin = repo.login(email, resetPassword)
            assertTrue(
                relogin is LoginResult.Verified || relogin is LoginResult.Unverified,
                "the new password must log in (session issued)",
            )
        } finally {
            mailClient.close()
        }
    }

    @Test
    fun live_verify_verifyEmail_ok_thenVerifiedFlipLive() = live { repo, _ ->
        val mailClient = HttpClient(CIO)
        try {
            val email = uniqueEmail()
            repo.register(email, strongPassword)                                      // verification-first → sends a verify mail
            val link = verificationLink(mailText(mailClient, email, "verify your email")) // masked: never logged
            assertEquals(VerifyResult.Ok, repo.verifyEmail(link))
            // Sharpened #2: the verified-flip is LIVE — after verify, login → /api/auth/me verified:true.
            assertEquals(LoginResult.Verified, repo.login(email, strongPassword))
            assertEquals(SessionState.Verified, repo.session()) // closes the P2.3 linkage live
        } finally {
            mailClient.close()
        }
    }

    @Test
    fun live_recovery_wrongCode_mapsTokenInvalid() = live { repo, _ ->
        val email = uniqueEmail()
        repo.register(email, strongPassword)
        repo.requestReset(email) // opens the recovery flow
        // fail-closed teeth: a wrong/expired code must map to TokenInvalid, never a silent success.
        assertEquals(SetPasswordResult.TokenInvalid, repo.setNewPassword("000000", resetPassword))
    }
}
