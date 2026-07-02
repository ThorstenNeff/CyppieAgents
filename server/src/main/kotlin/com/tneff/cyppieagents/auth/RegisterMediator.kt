package com.tneff.cyppieagents.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
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
 *  - **MUST-2 (timing parity):** two layers. (1) the branch-DIVERGENT work (create+verify-mail for new /
 *    notice-mail for existing) is [dispatch]ed OFF the response path. (2) CYP-179 (stage-2, Aiven-Postgres) — a
 *    **constant-time floor** ([floorMs]) pads EVERY response to the same minimum time from register-start, so the
 *    existence check's own found/miss latency tell never reaches the wire. On a remote DB the Kratos admin
 *    `?credentials_identifier=` FOUND path hydrates the identity (extra round-trips) while a MISS short-circuits
 *    → a measured +12.8ms found>miss oracle (v1.3.0 has no hydration-free existence check); the floor masks it
 *    structurally. Every response observable (status/body/headers) is identical (enforced by [registerRoutes]).
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
    /**
     * CYP-179 (stage-2) — the **constant-time floor** (ms). Every response is padded to ≥ this many ms from
     * register-start, found/miss-BLIND, so the existence check's found/miss latency tell never reaches the wire.
     * MUST be **> the found-branch absolute worst-case** response time (not just > the delta) so BOTH branches
     * pad UP to the same value — single-sourced from deploy's Aiven-Postgres N=40 found-path max + jitter margin.
     * `0` = off (the default, so the hermetic non-timing tests stay fast); production wires it from config.
     */
    private val floorMs: Long = 0,
    /**
     * The existence-check timeout (ms): a check slower than this → [RegisterOutcome.UNAVAILABLE] (a uniform 503),
     * so a hang or a jitter spike can never surface as a found/miss timing tell. Must be comfortably > [floorMs].
     * Defaulted large so instant-fake tests never trip it.
     */
    private val clampMs: Long = 60_000,
) {
    constructor(
        backend: KratosRegisterBackend,
        scope: CoroutineScope,
        floorMs: Long = 0,
        clampMs: Long = 60_000,
    ) : this(backend, { block -> scope.launch { block() } }, floorMs, clampMs)

    private val log = LoggerFactory.getLogger("auth.register")

    suspend fun register(email: String, password: String): RegisterOutcome {
        val startNanos = System.nanoTime()
        val outcome = try {
            // MUST-2: the check runs for BOTH branches. The clamp turns a hang/spike into a uniform outage
            // (below) rather than a slow, found/miss-correlated response.
            val exists = withTimeout(clampMs) { backend.identityExists(email) }
            // MUST-2: the divergent work is deferred OFF the response path (create+verify / notice). The existing
            // branch enqueues a notice mail so it is mail-symmetric (no "silence == exists" side-channel).
            dispatch {
                try {
                    if (exists) backend.notifyExisting(email) else backend.createAndVerify(email, password)
                } catch (e: Exception) {
                    // Branch-BLIND: log only the failure class — never the email/password.
                    log.warn("register side-effect failed: {}", e.javaClass.simpleName)
                }
            }
            RegisterOutcome.ACCEPTED
        } catch (e: TimeoutCancellationException) {
            // MUST-3: a check slower than the clamp → uniform outage (found/miss-blind), NO create.
            log.warn("register existence-check exceeded clamp ({}ms) → fail-closed", clampMs)
            RegisterOutcome.UNAVAILABLE
        } catch (e: CancellationException) {
            throw e // a genuine cancellation (e.g. the client disconnected) — never swallow it
        } catch (e: Exception) {
            // MUST-3: admin outage → uniform, branch-independent 503 for ALL; NO create. Never logs the email.
            log.warn("register existence-check failed (fail-closed, no create): {}", e.javaClass.simpleName)
            RegisterOutcome.UNAVAILABLE
        }
        // CYP-179 (stage-2) constant-time floor: pad EVERY outcome (200 AND 503) to ≥ floorMs from start, so the
        // response time is a constant — not a function of found/miss. Closes the Aiven-Postgres +12.8ms
        // hydration-round-trip tell structurally at our boundary. `delay` never undershoots (elapsedMs is
        // truncated ≤ actual) and is cooperative (costs no thread).
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        if (elapsedMs < floorMs) delay(floorMs - elapsedMs)
        return outcome
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
