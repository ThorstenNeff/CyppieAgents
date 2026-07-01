package com.tneff.cyppieagents.auth

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.ApiError
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.routing.ApiException
import com.tneff.cyppieagents.routing.TokenRegistry
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CYP-178 / **RC2 — hermetic Config-Assertion** (the merge-gate half; the LIVE behavioral probe against
 * dev-Kratos is the deploy-coordinated real-path gate). Three checks the backend owns:
 *
 *  1. The shipped Kratos security-reference config keeps the account-enumeration + constant-time knobs set.
 *  2. The guard leaks **no enumeration oracle**: absent, invalid, and unverified sessions are byte-identical 401s.
 *  3. `KratosIdentityProvider` is **bounded** — a hung Kratos resolves to null well within the timeout, never
 *     hanging the guard (C1 availability).
 *  4. **CC2** — the login brute-force posture (§8 flag-1): the per-account defense is the argon2 cost floor
 *     with **no hard account-lockout** (a lockout is a victim-DoS); per-IP rate-limiting is edge (CYP-179).
 */
class Rc2ConfigAssertionTest {

    // ---- 1. the shipped Kratos reference config keeps its security knobs ----

    @Test
    fun kratosReferenceConfig_keepsAntiEnumerationAndConstantTimeKnobs() {
        val text = repoFile("deploy/kratos/kratos.reference.yml").readText()
        // Version pin (deploy-found drift): the schema below is authored for Kratos v1.3.0; brew ships an
        // incompatible v26 (schema break). A bump here without re-validating the schema reds this line.
        assertTrue(Regex("(?m)^version:\\s*v1\\.3\\.0\\s*$").containsMatchIn(text), "Kratos config must stay pinned to v1.3.0 (brew's v26 is schema-incompatible)")
        // The session cookie name MUST equal the constant the guard reads (single-source — drift = red).
        assertTrue(text.contains("name: $KRATOS_SESSION_COOKIE"), "session cookie name must match KRATOS_SESSION_COOKIE")
        // Modern two-step registration (the enumeration-safe flow).
        assertTrue(Regex("enable_legacy_one_step:\\s*false").containsMatchIn(text), "registration must be enumeration-safe (enable_legacy_one_step: false)")
        // Recovery + verification must NOT reveal whether an address exists (both set to false).
        assertEquals(2, Regex("notify_unknown_recipients:\\s*false").findAll(text).count(), "recovery AND verification must set notify_unknown_recipients: false")
        // Constant-cost password hashing → no login-timing oracle.
        assertTrue(Regex("algorithm:\\s*argon2").containsMatchIn(text), "password hashing must be argon2 (constant-cost)")
        // RC2 live-probe finding: the MASTER enumeration switch (dummy-hash for absent identifiers → login
        // timing equalized; generic register/recovery). The per-flow knobs don't cover the timing channel.
        assertTrue(
            Regex("account_enumeration:\\s*\\n\\s*mitigate:\\s*true").containsMatchIn(text),
            "security.account_enumeration.mitigate must be true (closes the login-timing enumeration oracle)",
        )
        // Session cookie SameSite hardening (defense-in-depth alongside the platform CSRF double-submit).
        assertTrue(Regex("same_site:\\s*(Lax|Strict)").containsMatchIn(text), "session cookie must be SameSite Lax/Strict")
        // RC4: the privileged Kratos ADMIN API must be loopback-bound — never internet-exposed. The platform
        // never calls it (identities self-serve via Kratos; roles are the platform's own SqliteRoleStore), so
        // the only obligation is that the shipped config keeps it off the public interface.
        assertTrue(
            Regex("base_url:\\s*http://127\\.0\\.0\\.1:").containsMatchIn(text),
            "RC4: the Kratos admin base_url must be loopback-bound (127.0.0.1)",
        )
    }

    // ---- CC2: the §8 flag-1 binding target — per-account brute-force posture, NO hard-lockout ----

    @Test
    fun kratosReferenceConfig_loginBruteForcePosture_bindsFlag1() {
        val text = repoFile("deploy/kratos/kratos.reference.yml").readText()
        // OSS Kratos has no per-account attempt-lockout knob and never locks accounts by design (a lockout
        // is a victim-DoS, reviewer flag-1). So the per-account defense is the argon2 per-attempt COST FLOOR:
        // argon2 with a meaningful iteration count → each online guess is deliberately expensive.
        val iterations = Regex("iterations:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toInt()
            ?: fail("argon2 iterations (the per-account brute-force cost floor) must be set")
        assertTrue(iterations >= 2, "argon2 iterations must be a meaningful cost floor (>= 2), was $iterations")
        assertTrue(Regex("memory:\\s*\\d+MB").containsMatchIn(text), "argon2 memory cost must be set (per-attempt expense)")
        // The explicit no-lockout posture marker — the reviewer/§8 flag-1 line binds here: NO account-lockout
        // (avoids victim-DoS); the real request-rate throttle is per-IP at the edge (CYP-179), not in Kratos.
        assertTrue(
            text.contains("CC2-NO-ACCOUNT-LOCKOUT"),
            "the reference config must declare the explicit no-hard-lockout posture (§8 flag-1 binding target)",
        )
        // Defensive: no accidental lockout/max-attempts knob crept in (Kratos would ignore it, but its
        // presence would signal the wrong intent — fail closed on the posture).
        assertTrue(
            !Regex("(?i)(max_login_attempts|account_lockout|lockout_duration)\\s*:").containsMatchIn(text),
            "no account-lockout knob may be configured (no victim-DoS) — per-IP throttling belongs at the edge (CYP-179)",
        )
    }

    // ---- 2. no enumeration oracle at the guard: absent / invalid / unverified are indistinguishable ----

    @Test
    fun guard_leaksNoOracle_absentInvalidUnverifiedAreIdentical401() = testApplication {
        val db = Files.createTempFile("rc2-roles", ".db")
        val deps = AuthDeps(
            tokens = TokenRegistry(emptyMap(), operatorToken = "tok-op"),
            idp = FakeIdentityProvider(mapOf("unverified" to ResolvedIdentity("bob", verified = false))),
            roles = SqliteRoleStore(db),
            nowMs = { 1L },
        )
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) {
                exception<ApiException> { call, cause -> call.respond(cause.status, ApiErrorBody(ApiError(cause.code, cause.message))) }
            }
            routing { authenticatedApi(deps, AuthRole.OPERATOR) { get("/api/secret") { call.respondText("ok") } } }
        }
        val absent = client.get("/api/secret")
        val invalid = client.get("/api/secret") { header("X-Session-Token", "not-a-real-session") }
        val unverified = client.get("/api/secret") { header("X-Session-Token", "unverified") }

        // Identical status AND identical body — nothing distinguishes "no session" from "wrong" from "unverified".
        assertEquals(io.ktor.http.HttpStatusCode.Unauthorized, absent.status)
        assertEquals(absent.status, invalid.status)
        assertEquals(absent.status, unverified.status)
        val a = absent.bodyAsText(); val b = invalid.bodyAsText(); val c = unverified.bodyAsText()
        assertEquals(a, b, "invalid session must be byte-identical to absent (no oracle)")
        assertEquals(a, c, "unverified session must be byte-identical to absent (no oracle)")
    }

    // ---- 3. bounded timeout: a hung Kratos never hangs the guard ----

    private val hungKratos = embeddedServer(Netty, port = 0) {
        routing { get("/sessions/whoami") { delay(60_000); call.respondText("late") } }
    }.start(wait = false)
    private val hungPort = runBlocking { hungKratos.engine.resolvedConnectors().first().port }

    @AfterTest fun stopHung() = hungKratos.stop(100, 100)

    @Test
    fun whoami_boundedUnderHang_returnsNullNeverHangs() = runBlocking {
        val idp = KratosIdentityProvider("http://localhost:$hungPort/sessions/whoami", timeoutMs = 500)
        // If the timeout were unbounded, the 60s server delay would blow this withTimeout → test fails.
        val result = withTimeout(5_000) { idp.resolve(SessionCredential("some-session", SessionCredential.Source.HEADER)) }
        assertNull(result, "a hung Kratos must fail closed to null, bounded by the request timeout")
    }

    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        fail("could not locate '$rel' from ${System.getProperty("user.dir")}")
    }
}
