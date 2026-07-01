package com.tneff.cyppieagents.auth

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.routing.ApiException
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.settingsRoutes
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CYP-181 / P2.4 — the MEMBER-guarded settings shim. A fake Kratos records the session it was called with and
 * echoes a distinctive outcome; the tests prove **thin passthrough**, **self-scoping** (the acted-on identity
 * is the caller's session, never a body `identityId`), and **session-required** (a machine caller with no
 * session is 403). The **no-secret-in-logs** (a/C) is a source-scan.
 */
class SettingsRoutesTest {

    @Volatile private var receivedSessionToken: String? = null

    private val fakeKratos = embeddedServer(Netty, port = 0) {
        routing {
            get("/self-service/settings/api") {
                if (poisoned(call)) { call.respondText("both-headers-poisoned", status = HttpStatusCode.InternalServerError); return@get }
                val base = "http://localhost:${call.request.local.localPort}"
                call.respondText("""{"ui":{"action":"$base/self-service/settings?flow=f1"}}""", ContentType.Application.Json)
            }
            post("/self-service/settings") {
                if (poisoned(call)) { call.respondText("both-headers-poisoned", status = HttpStatusCode.InternalServerError); return@post }
                receivedSessionToken = call.request.header("X-Session-Token")
                // Distinctive outcome the shim must pass through UNCHANGED.
                call.respondText("""{"state":"success","kratos":"real-response"}""", ContentType.Application.Json, HttpStatusCode.OK)
            }
        }
    }.start(wait = false)

    // Model Kratos v1.3.0: a request carrying BOTH X-Session-Token AND the ory_kratos_session cookie is
    // poisoned → 500 (the real-path bug). If the settings shim ever regresses to sending both, the passthrough
    // test reds — the teeth that were missing when only X-Session-Token was read.
    private fun poisoned(call: io.ktor.server.application.ApplicationCall): Boolean =
        call.request.header("X-Session-Token") != null && call.request.cookies["ory_kratos_session"] != null
    private val kratosPort = runBlocking { fakeKratos.engine.resolvedConnectors().first().port }

    @AfterTest fun stop() = fakeKratos.stop(100, 100)

    private fun ApplicationTestBuilder.installSettings(): SqliteRoleStore {
        val db = Files.createTempFile("settings-roles", ".db")
        val store = SqliteRoleStore(db)
        val deps = AuthDeps(
            tokens = TokenRegistry(emptyMap(), operatorToken = "tok-op"),
            idp = FakeIdentityProvider(
                mapOf(
                    "sess-member" to ResolvedIdentity("member", verified = true),
                    "sess-unverified" to ResolvedIdentity("unverified-member", verified = false),
                ),
            ),
            roles = store,
            nowMs = { 1L },
        )
        val kratos = KratosSettingsClient(publicBaseUrl = "http://localhost:$kratosPort")
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) { exception<ApiException> { call, cause -> call.respond(cause.status) } }
            routing { settingsRoutes(deps, kratos) }
        }
        return store
    }

    @Test
    fun password_verifiedMember_thinPassthrough_forwardsCallerSession() = testApplication {
        val store = installSettings()
        val r = client.post("/api/auth/settings/password") {
            header("X-Session-Token", "sess-member"); contentType(ContentType.Application.Json)
            setBody("""{"newPassword":"a-new-secret-pw"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("real-response"), "the shim must pass the Kratos response through unchanged")
        assertEquals("sess-member", receivedSessionToken, "the shim must authenticate Kratos with the CALLER's session")
        store.close()
    }

    @Test
    fun email_selfScoped_bodyIdentityIdIsIgnored_scopeStaysTheSession() = testApplication {
        val store = installSettings()
        // A malicious body carries someone else's identityId — it MUST be ignored (self-scoped): the shim
        // still acts on the caller's own session, never the body id.
        val r = client.post("/api/auth/settings/email") {
            header("X-Session-Token", "sess-member"); contentType(ContentType.Application.Json)
            setBody("""{"newEmail":"new@cyppie.dev","identityId":"victim-identity"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("sess-member", receivedSessionToken, "self-scoped: the acted-on identity is the session, not the body identityId")
        store.close()
    }

    @Test
    fun machineOperator_noSession_is403_sessionRequired() = testApplication {
        val store = installSettings()
        // The operator token authenticates (MEMBER satisfied) but has no Kratos session → nothing to change.
        val r = client.post("/api/auth/settings/password") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json); setBody("""{"newPassword":"x"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        store.close()
    }

    // ---- guard teeth: the MEMBER guard MUST bite where it can break (un-guarding must red these) ----

    @Test
    fun unverifiedSession_settings_is401_verifiedGuardBites() = testApplication {
        val store = installSettings()
        // A valid-but-UNVERIFIED session must be rejected by the guard (RC1). Un-guarding the route
        // (authenticatedApi→apply{}) would let the handler run (the session_required check passes, the
        // session IS present) → NOT 401 → this reds, catching the removed guard.
        val r = client.post("/api/auth/settings/password") {
            header("X-Session-Token", "sess-unverified"); contentType(ContentType.Application.Json)
            setBody("""{"newPassword":"x"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        store.close()
    }

    @Test
    fun cookieSession_settings_withoutCsrf_is403_csrfGuardBites() = testApplication {
        val store = installSettings()
        // A COOKIE-authed state-changing POST without X-CSRF-Token must 403 (RC5 double-submit, enforced in
        // the guard). Un-guarding would skip enforceCsrf → the handler runs → NOT 403 → this reds.
        val r = client.post("/api/auth/settings/password") {
            header(io.ktor.http.HttpHeaders.Cookie, "$KRATOS_SESSION_COOKIE=sess-member")
            contentType(ContentType.Application.Json); setBody("""{"newPassword":"x"}""")
            // deliberately NO X-CSRF-Token
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        store.close()
    }

    @Test
    fun settingsSource_neverLogsTheNewSecret() {
        // (a/C) grep-assert: no logger call in the settings shim may reference the new password / email value.
        val sources = listOf(
            repoFile("server/src/main/kotlin/com/tneff/cyppieagents/routing/SettingsRoutes.kt"),
            repoFile("server/src/main/kotlin/com/tneff/cyppieagents/auth/KratosSettingsClient.kt"),
        )
        val offending = Regex("""(?i)log\w*\.(trace|debug|info|warn|error)\([^)]*\b(newPassword|newEmail|password|payload|req\.\w+)\b""")
        for (f in sources) {
            val m = offending.find(f.readText())
            assertTrue(m == null, "${f.name} logs a secret-bearing value: '${m?.value}' — new pw/email must never be logged")
        }
    }

    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) { val f = File(dir, rel); if (f.exists()) return f; dir = dir.parentFile }
        fail("could not locate '$rel'")
    }
}
