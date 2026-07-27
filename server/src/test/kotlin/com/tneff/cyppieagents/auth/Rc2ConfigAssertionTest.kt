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
import kotlin.test.assertFalse
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

    // ---- CYP-562: return-URL allow-list open-redirect guardrail ----

    @Test
    fun kratosReferenceConfig_returnUrlAllowlist_hasOpenRedirectGuardrail() {
        // The M1-auth re-read (CYP-550 follow-on) found the return-URL allow-list had NO test binding — unlike the
        // enumeration / cookie / admin knobs above. Kratos 302s the browser to the post-login `?return_to=` target
        // ONLY if it matches selfservice.allowed_return_urls, so a too-broad entry (wildcard / prefix / scheme-only /
        // all-interfaces) is a post-auth open redirect. This binds the boundary: the key must be present, the fixed
        // desktop loopback must be an explicit entry, and no concrete entry may be over-broad.
        val text = repoFile("deploy/kratos/kratos.reference.yml").readText()
        assertTrue(
            Regex("(?m)^\\s*allowed_return_urls:").containsMatchIn(text),
            "selfservice.allowed_return_urls must be present (Kratos gates post-login return_to against it)",
        )
        // Isolate the selfservice return-URL region so the value checks below can't false-match unrelated URLs
        // elsewhere in the config (the region runs from `selfservice:` — so it includes the CYP-562 comment above
        // default_browser_return_url — to the next `methods:` key). Comment-only lines parse to empty values below.
        val region = Regex("(?s)selfservice:.*?\\n\\s*methods:").find(text)?.value
            ?: fail("could not locate the selfservice return-URL region (selfservice: … methods:)")
        // The security-invariant comment MUST be present so the deploy operator sees the exact-origin / no-wildcard
        // rule at the point of edit (the placeholder alone doesn't convey the constraint).
        assertTrue(region.contains("CYP-562"), "the return-URL block must carry the CYP-562 open-redirect security comment")
        // Collect the CONCRETE return-URL values (strip inline comments so the doc comment's own `*` is not scanned).
        val values = region.lines()
            .map { it.substringBefore('#').trim() }
            .mapNotNull { line ->
                when {
                    line.startsWith("- ") -> line.removePrefix("- ").trim()
                    line.startsWith("default_browser_return_url:") -> line.substringAfter(':').trim()
                    else -> null
                }
            }
            .filter { it.isNotEmpty() }
        assertTrue(values.isNotEmpty(), "the return-URL region must declare at least one value")
        // The desktop loopback return is FIXED + deploy-invariant (RFC 8252; desktopApp main.kt LOOPBACK_PORT=47472),
        // so pin it — a deploy edit can't silently drop it (which would break the desktop OIDC return or tempt a
        // too-broad entry to "make it work"). Host:port form is asserted (robust to origin-vs-path matching form).
        assertTrue(
            values.any { it.contains("127.0.0.1:47472") },
            "the fixed desktop loopback (127.0.0.1:47472) must be an explicit allow-list entry",
        )
        // Every CONCRETE (non-placeholder) entry must be an EXACT absolute http(s) origin/URL, never over-broad. An
        // un-substituted REPLACE_ME_* placeholder is the deploy's job (a `grep REPLACE_ME` completeness check catches it).
        for (v in values.filterNot { it.startsWith("REPLACE_ME") }) {
            assertFalse(v.contains("*"), "return-URL entry \"$v\" must not contain a wildcard (post-auth open redirect)")
            assertFalse(v.contains("0.0.0.0"), "return-URL entry \"$v\" must not bind all interfaces (open redirect)")
            assertFalse(Regex("^https?://$").matches(v), "return-URL entry \"$v\" must not be scheme-only (matches every host)")
            assertTrue(
                Regex("^https?://[^/*]+").containsMatchIn(v),
                "return-URL entry \"$v\" must be an EXACT absolute http(s) origin/URL",
            )
        }
    }

    // ---- CYP-190: Kratos-side log hardening (token/code leak into kratos.log) ----

    @Test
    fun kratosReferenceConfig_logHardening_noSensitiveLeak() {
        // Kratos logs its OWN request handling; without a pinned `log:` block the box ran leaky defaults and
        // wrote cleartext session tokens (ory_st_…) + recovery/verification codes into kratos.log (deploy found
        // 259). The master redaction switch is `leak_sensitive_values` and the level must not log request URIs.
        // (This is the Kratos-side analog of the hub's BG-WS-5 logback redaction — a separate process.)
        val text = repoFile("deploy/kratos/kratos.reference.yml").readText()
        // ANCHORED to a real config line (`^…$`) — an un-anchored match would also be satisfied by the yml
        // DOC-COMMENT mention of `leak_sensitive_values: false`, so deleting the config value while keeping the
        // comment would false-green while Kratos ran the leaky default (Test's demonstration; the CYP-184 lesson
        // — assert the VALUE, not the form). The comment line starts with `#` and has trailing prose → excluded.
        assertTrue(
            Regex("(?m)^\\s*leak_sensitive_values:\\s*false\\s*$").containsMatchIn(text),
            "log.leak_sensitive_values must be false on a real config line — Kratos redacts tokens/codes from its logs",
        )
        // Teeth: `true` is exactly what leaked the 259 tokens → it must NEVER appear.
        assertTrue(
            !Regex("leak_sensitive_values:\\s*true").containsMatchIn(text),
            "log.leak_sensitive_values must NEVER be true (that leaks tokens/codes into kratos.log)",
        )
        assertTrue(
            Regex("(?m)^\\s*level:\\s*info\\s*$").containsMatchIn(text),
            "log.level must be info (debug/trace would log token-bearing request URIs)",
        )
        // Teeth: a debug/trace level logs request detail incl. `?token=`/codes → must NOT appear.
        assertTrue(
            !Regex("level:\\s*(debug|trace)").containsMatchIn(text),
            "log.level must NOT be debug/trace (logs token-bearing request detail)",
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
            tokens = TokenRegistry(emptyMap(), operatorToken = "tok-op", loopbackPosture = true),
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
