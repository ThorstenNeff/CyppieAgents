package com.tneff.cyppieagents.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CYP-182 flip — the config-gated selection logic, all THREE reviewer-frozen branches (mutation-hardened,
 * not just the happy path): flag absent → Stub; flag + valid URLs → Live; flag set but URLs missing/malformed
 * → **fail-loud** (Misconfigured/throw), NEVER a silent stub downgrade.
 */
class AuthFlipTest {

    private val goodOrigin = "http://127.0.0.1:8787"
    private val goodProxy = "http://127.0.0.1:8088/.ory/kratos/public"

    // --- Branch 1: flag absent/blank/falsey → Stub (Dev/Demo default) ---

    @Test
    fun flagAbsent_resolvesStub() {
        assertEquals(AuthMode.Stub, resolveAuthMode(AuthLiveEnv(null, null, null)))
        // Even with URLs present, no truthy flag → Stub (the flag is the gate).
        assertEquals(AuthMode.Stub, resolveAuthMode(AuthLiveEnv(null, goodOrigin, goodProxy)))
        assertEquals(AuthMode.Stub, resolveAuthMode(AuthLiveEnv("   ", goodOrigin, goodProxy)))
        assertEquals(AuthMode.Stub, resolveAuthMode(AuthLiveEnv("false", goodOrigin, goodProxy)))
        assertEquals(AuthMode.Stub, resolveAuthMode(AuthLiveEnv("0", goodOrigin, goodProxy)))
    }

    // --- Branch 2: flag on + complete valid URLs → Live ---

    @Test
    fun flagOn_withValidUrls_resolvesLive() {
        val m = resolveAuthMode(AuthLiveEnv("1", goodOrigin, goodProxy))
        assertIs<AuthMode.Live>(m)
        assertEquals(goodOrigin, m.platformBaseUrl)
        assertEquals(goodProxy, m.kratosProxyUrl)
        // truthy variants + trimming
        assertIs<AuthMode.Live>(resolveAuthMode(AuthLiveEnv("true", goodOrigin, goodProxy)))
        assertIs<AuthMode.Live>(resolveAuthMode(AuthLiveEnv(" YES ", " $goodOrigin ", " $goodProxy ")))
        assertIs<AuthMode.Live>(resolveAuthMode(AuthLiveEnv("on", "https://auth.example.com", "https://auth.example.com/.ory/kratos/public")))
    }

    // --- Branch 3: flag on but URLs missing/malformed → Misconfigured (fail-loud, NOT Stub) ---

    @Test
    fun flagOn_missingOrMalformedUrls_resolvesMisconfigured_neverStub() {
        val cases = listOf(
            AuthLiveEnv("1", null, goodProxy),         // origin missing
            AuthLiveEnv("1", goodOrigin, null),        // proxy missing
            AuthLiveEnv("1", "", ""),                  // both blank
            AuthLiveEnv("1", "not-a-url", goodProxy),  // origin malformed (no scheme)
            AuthLiveEnv("1", goodOrigin, "ftp://x"),   // proxy wrong scheme
            AuthLiveEnv("1", "http://", goodProxy),    // origin has scheme but no host
        )
        for (env in cases) {
            val m = resolveAuthMode(env)
            assertIs<AuthMode.Misconfigured>(m)
            assertTrue(m !is AuthMode.Stub, "misconfigured live must NOT silently downgrade to Stub: $env")
        }
    }

    // --- Selection: Stub → stub · Live → the injected factory · Misconfigured → throw (fail-loud) ---

    @Test
    fun stubMode_selectsStubRepository() {
        assertIs<StubAuthRepository>(authRepositoryFor(AuthMode.Stub))
    }

    @Test
    fun liveMode_usesLiveFactory_notTheStubBranch() {
        val fromFactory = StubAuthRepository() // a distinct instance, used only as an identity sentinel
        var called = false
        val repo = authRepositoryFor(AuthMode.Live(goodOrigin, goodProxy)) { called = true; fromFactory }
        assertTrue(called, "Live must go through the live factory")
        assertTrue(repo === fromFactory, "Live must return the factory's repository, not a fresh stub")
    }

    @Test
    fun misconfiguredMode_failsLoud_notSilentStub() {
        assertFailsWith<AuthConfigException> { authRepositoryFor(AuthMode.Misconfigured("bad URLs")) }
    }
}
