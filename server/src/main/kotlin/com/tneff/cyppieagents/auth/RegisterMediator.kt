package com.tneff.cyppieagents.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * CYP-179 / §B(b) — the platform **register-wrapper**, the fix for the Kratos v1.3.0 registration-content
 * account-enumeration leak (RC2 §B). Raw Kratos register reveals existence in one deterministic request
 * (new email → 200 + creates; existing → 400 `4000007`) AND writes an identity on the new branch (store-
 * pollution / registration-DoS). This wrapper is the platform's ONLY app register path; it collapses BOTH
 * branches to a **byte-identical, branch-invariant response** so the caller learns nothing about existence,
 * and gates the account-creation side-effect behind a quiet existence check (no create for an existing email).
 *
 * The three Reviewer MUSTs (mutation-teethed in the tests) are structural here:
 *  - **MUST-2 (timing parity):** the existence check runs UNCONDITIONALLY for both branches (symmetric cost),
 *    and the branch-DIVERGENT work (create+verify-mail for new / notice-mail for existing) is [dispatch]ed
 *    OFF the response path — so response latency is branch-invariant. Every response observable (status/body/
 *    headers) is identical (enforced by [com.tneff.cyppieagents.routing.registerRoutes] via one constant body).
 *  - **MUST-3 (fail-closed):** an existence-check outage (admin :4434 down) → [RegisterOutcome.UNAVAILABLE]
 *    (a uniform, email-INDEPENDENT response for everyone) and **no create is attempted** — never a
 *    branch-dependent error that would re-open the oracle.
 *  - **MUST-1 (register-only scope):** the [KratosRegisterBackend] seam exposes ONLY existence + the two
 *    register side-effects — it is NOT a general Kratos admin proxy.
 *
 * Secret hygiene: the email/password are passed only to the backend; NEVER logged (only failure classes are).
 */
class RegisterMediator(
    private val backend: KratosRegisterBackend,
    /**
     * Runs the branch-divergent side-effect OFF the response path (MUST-2). Injected so tests dispatch it
     * deterministically AND prove the response returns without awaiting it. Real wiring launches on a scope.
     */
    private val dispatch: (suspend () -> Unit) -> Unit,
) {
    constructor(backend: KratosRegisterBackend, scope: CoroutineScope) :
        this(backend, { block -> scope.launch { block() } })

    private val log = LoggerFactory.getLogger("auth.register")

    suspend fun register(email: String, password: String): RegisterOutcome {
        val exists = try {
            backend.identityExists(email) // MUST-2: run for BOTH branches (symmetric); the ONLY sync-path variance is the boolean.
        } catch (e: Exception) {
            // MUST-3: admin outage → uniform, branch-independent response for ALL; NO create attempted. Never logs the email.
            log.warn("register existence-check failed (fail-closed, no create): {}", e.javaClass.simpleName)
            return RegisterOutcome.UNAVAILABLE
        }
        // MUST-2: the divergent work is deferred → the sync path (check + dispatch + respond) is identical for
        // new and existing, so response latency does not leak existence. The existing branch enqueues a notice
        // mail so it is mail-symmetric with the new branch (no "silence == exists" side-channel).
        dispatch {
            try {
                if (exists) backend.notifyExisting(email) else backend.createAndVerify(email, password)
            } catch (e: Exception) {
                // Branch-BLIND: log only the failure class — never the email/password.
                log.warn("register side-effect failed: {}", e.javaClass.simpleName)
            }
        }
        return RegisterOutcome.ACCEPTED
    }
}

/** The wrapper's branch-invariant verdict. Both map to a CONSTANT response body (see registerRoutes). */
enum class RegisterOutcome { ACCEPTED, UNAVAILABLE }

/**
 * The **register-only** seam to Kratos (MUST-1) — NOT a general admin proxy. Exactly three operations:
 * a synchronous existence check (response-critical), and the two async register side-effects. The real
 * implementation ([HttpKratosRegisterBackend]) talks to the loopback Kratos ADMIN API (:4434, RC4); its
 * exact create/verify-mail mechanics on Kratos v1.3.0 are the deploy-verified seam (see the CYP-179 runbook).
 */
interface KratosRegisterBackend {
    /** SYNC existence check for an email (admin :4434). Throws on outage → the mediator returns UNAVAILABLE (MUST-3). */
    suspend fun identityExists(email: String): Boolean

    /** NEW branch (async): create the identity with the password + trigger the verification mail. */
    suspend fun createAndVerify(email: String, password: String)

    /** EXISTING branch (async): send the "you already have an account" notice — keeps the branches mail-symmetric. */
    suspend fun notifyExisting(email: String)
}
