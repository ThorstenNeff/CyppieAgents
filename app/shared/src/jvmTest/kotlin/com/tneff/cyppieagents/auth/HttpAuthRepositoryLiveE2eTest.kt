package com.tneff.cyppieagents.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.cookies.HttpCookies
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * CYP-182 — **LIVE** end-to-end for [HttpAuthRepository] against the RUNNING platform-auth stack
 * (server + Kratos v1.3.0 + Caddy same-origin proxy). The real-path counterpart of the hermetic
 * [HttpAuthRepositoryE2eTest]: same repo, same branches, but against **real Kratos** — it proves the wire
 * shapes, the native `X-Session-Token` capture+replay, and the live `sessions/whoami` boot-echo on the real
 * chain. This is the **P3 real-path gate**; the PO-gated stub→real default flip depends on its GO.
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
 * ## Coverage
 * Live: `session()` unauth → None; self-service **register** a fresh identity → Pending, then `session()` →
 * AuthedUnverified with the live whoami email echo (the intended granular `/api/auth/me`
 * `{authenticated:true, verified:false}`); **login** the pre-verified [CYPPIE_AUTH_EMAIL] → Verified (skipped
 * if no password given); **recovery request** + **resend** → neutral Accepted; **logout** → None.
 *
 * NOT automated: the **deep-link completion** (`setNewPassword`/`verifyEmail` with the emailed code) — it
 * needs a mailbox to read the code. The init/neutral halves ARE covered live; the completion mapping stays
 * proven hermetically ([HttpAuthRepositoryE2eTest]) until a test-mailbox / Kratos courier-dump is wired.
 */
class HttpAuthRepositoryLiveE2eTest {

    private val enabled = System.getenv("CYPPIE_AUTH_E2E") == "1"
    private val origin = System.getenv("CYPPIE_AUTH_ORIGIN") ?: "http://127.0.0.1:8787"
    private val proxy = System.getenv("CYPPIE_AUTH_PROXY") ?: "http://127.0.0.1:8088/.ory/kratos/public"
    private val verifiedEmail = System.getenv("CYPPIE_AUTH_EMAIL") ?: "rc2-test@cyppie.dev"
    private val verifiedPassword: String? = System.getenv("CYPPIE_AUTH_PASSWORD")

    private val strongPassword = "Cyppie-Live-E2E-9x!"

    private fun uniqueEmail() = "live-${UUID.randomUUID().toString().substring(0, 8)}@cyppie.dev"

    /** A fresh client+repo. HttpCookies so the browser-cookie path also works if the stack sets a cookie. */
    private fun freshRepo(): Pair<HttpAuthRepository, HttpClient> {
        val client = HttpClient(CIO) { install(HttpCookies) }
        val repo = HttpAuthRepository(client, origin, kratosBaseUrl = proxy, sessionStore = InMemoryAuthSessionStore())
        return repo to client
    }

    private fun live(block: suspend (HttpAuthRepository) -> Unit) {
        if (!enabled) return // no-op without the stack → the normal gate stays green
        runBlocking {
            val (repo, client) = freshRepo()
            try {
                block(repo)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun live_session_unauthenticated_mapsNone() = live { repo ->
        assertEquals(SessionState.None, repo.session()) // /api/auth/me is public → {authenticated:false}
    }

    @Test
    fun live_register_thenSession_authedUnverified_withWhoamiEcho() = live { repo ->
        val email = uniqueEmail()
        assertIs<RegisterResult.Pending>(repo.register(email, strongPassword))
        // Intended granular /api/auth/me: authenticated:true, verified:false → AuthedUnverified.
        val s = repo.session()
        assertIs<SessionState.Unverified>(s)
        assertEquals(email, s.email) // LIVE self-reflecting whoami echo (no platform PII)
    }

    @Test
    fun live_login_verifiedIdentity_mapsVerified() = live { repo ->
        val pw = verifiedPassword ?: return@live // needs the pre-verified identity's password
        assertEquals(LoginResult.Verified, repo.login(verifiedEmail, pw))
        assertEquals(SessionState.Verified, repo.session())
    }

    @Test
    fun live_requestReset_neutralAccepted() = live { repo ->
        // Enumeration-safe: a real Kratos recovery init answers neutrally whether or not the account exists.
        assertEquals(ResetRequestResult.Accepted, repo.requestReset(uniqueEmail()))
    }

    @Test
    fun live_resendVerification_afterRegister_accepted() = live { repo ->
        repo.register(uniqueEmail(), strongPassword) // establishes an unverified session
        assertEquals(ResendResult.Accepted, repo.resendVerification())
    }

    @Test
    fun live_logout_clearsSession() = live { repo ->
        repo.register(uniqueEmail(), strongPassword)
        repo.logout()
        assertEquals(SessionState.None, repo.session())
    }
}
