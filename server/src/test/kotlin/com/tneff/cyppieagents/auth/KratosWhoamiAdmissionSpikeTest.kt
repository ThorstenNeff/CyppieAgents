package com.tneff.cyppieagents.auth

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * CYP-183 (whoami real-path) — the **resolve-ADMISSION** check against LIVE Kratos, closing the gap that let
 * the dual-header bug through: the hermetic tests only ever used `FakeIdentityProvider`, so no test proved a
 * REAL session token is actually admitted by a real `GET /sessions/whoami`. This runs on the Kratos box with a
 * session token from a real login and asserts the provider resolves it (single `X-Session-Token`, un-poisoned).
 *
 * **RUN-gated + hermetic-safe:** no `KRATOS_PUBLIC_URL` / `KRATOS_TEST_SESSION_TOKEN` → no-op green (CI never
 * touches a live Kratos). Deploy extracts a native token from a real login and runs:
 *   `KRATOS_PUBLIC_URL=http://127.0.0.1:4433 KRATOS_TEST_SESSION_TOKEN=ory_st_… \`
 *   `  ./gradlew :server:test --tests "*KratosWhoamiAdmissionSpikeTest" --rerun-tasks`
 * (The browser-cookie path is covered by deploy's live-login re-verify + the hermetic matrix test.)
 */
class KratosWhoamiAdmissionSpikeTest {

    @Test
    fun realKratos_admitsNativeSessionToken() = runBlocking {
        val base = System.getenv("KRATOS_PUBLIC_URL")?.trimEnd('/') ?: return@runBlocking
        val token = System.getenv("KRATOS_TEST_SESSION_TOKEN")?.ifBlank { null } ?: return@runBlocking

        val idp = KratosIdentityProvider("$base/sessions/whoami")
        val resolved = idp.resolve(SessionCredential(token, SessionCredential.Source.HEADER))
        assertNotNull(
            resolved,
            "a real Kratos native session token must resolve — if this is null, whoami rejected it (dual-header poisoning regressed, or the token is invalid)",
        )
        println("whoami admission OK — real native session resolved: id=${resolved.identityId} verified=${resolved.verified}")
    }
}
