package com.tneff.cyppieagents.auth

import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-179 / §B(b) — the register-wrapper **live** seam probe against a real Kratos ADMIN API (:4434, loopback).
 *
 * **RUN-gated + hermetic-safe:** no `KRATOS_ADMIN_URL` → no-op green (CI never touches a live Kratos). Deploy
 * runs it with `KRATOS_ADMIN_URL=http://127.0.0.1:4434` against the dev stack (mirrors `Rc2LiveProbeTest` /
 * `OidcSpikeTest`). It verifies only the create/exists SEAM (`HttpKratosRegisterBackend`); the verify-mail /
 * notice-mail mechanics are Mailpit-verified per `deploy/kratos/CYP-179-REGISTER-WRAPPER-RUNBOOK.md`.
 *
 * ⚠️ It CREATES a throwaway identity — deploy runs `cleanup-junk-identities.sh` before/after (runbook).
 */
class RegisterWrapperLiveProbeTest {

    private val adminUrl: String? = System.getenv("KRATOS_ADMIN_URL")?.trimEnd('/')
    private val publicUrl: String? = System.getenv("KRATOS_PUBLIC_URL")?.trimEnd('/')

    @Test
    fun existenceCheckAndCreate_againstLiveAdmin() = runBlocking {
        val admin = adminUrl ?: return@runBlocking // hermetic no-op off the live stack
        val pub = publicUrl ?: return@runBlocking   // need the public URL for the C2 verification-flow trigger
        val backend = HttpKratosRegisterBackend(adminBaseUrl = admin, publicBaseUrl = pub)

        val fresh = "cyp179-probe-${UUID.randomUUID()}@example.org"
        assertFalse(backend.identityExists(fresh), "a random email must not exist before create (C1)")

        // C2: create + the verification-flow trigger (the follow-up that sends the verify mail). The create half
        // is asserted here; the mail landing in Mailpit is the deploy assertion (runbook C2).
        backend.createAndVerify(fresh, "probe-password-123456")
        assertTrue(backend.identityExists(fresh), "the identity must exist after createAndVerify (C2)")
    }
}
