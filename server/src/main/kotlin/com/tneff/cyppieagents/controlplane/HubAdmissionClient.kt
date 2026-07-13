package com.tneff.cyppieagents.controlplane

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.boot.ControlPlaneRegistrar
import com.tneff.cyppieagents.boot.NoOpControlPlaneConnector
import com.tneff.cyppieagents.crypto.HubIdentity
import com.tneff.cyppieagents.crypto.HubIdentityProvisioner
import com.tneff.cyppieagents.crypto.SecretStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import java.util.Base64

/**
 * CYP-512 (Epic CYP-427 Phase-2, activation) — the hub-side **live admission transport** (the real request-response
 * transport that replaces the one-way `ControlPlaneConnector` stub for admission — admission needs a
 * challenge→register→result round-trip, which a fire-and-forget egress can't model). Runs, outbound, against the CP:
 *  1. `GET  {cpBaseUrl}/api/cp/challenge` → a single-use PoP nonce.
 *  2. [ControlPlaneRegistrar.register] builds the signed registration (transcript PoP over the nonce, CYP-451),
 *     `ownerId` = the hub's configured owner (the operator — the CP re-checks it equals the authenticated operator).
 *  3. `POST {cpBaseUrl}/api/cp/admit` {registration, nonce} → the typed [HubAdmissionResult].
 *
 * Operator-authenticated (the operator's bearer/session). INERT until `cpBaseUrl` (CYPPIE_CP_URL) is configured — the
 * boot constructs this only behind that gate, fail-closed. Never sends a secret (only the public registration + PoP).
 *
 * ★ CYP-524 — **boot-window resilience**. The hub self-admits at boot by dialing its OWN edge (`CYPPIE_CP_URL` →
 * Caddy → `127.0.0.1:<port>` = this same process). The admit coroutine can fire BEFORE this process' HTTP listener
 * has bound, so the self-GET hits an edge whose upstream isn't up yet → a **non-JSON** body (Caddy 502, a warmup
 * page, connection-refused) that the old blind `.body<HubChallenge>()` turned into an opaque `SourceByteReadChannel`
 * with no retry — one lost race left the hub unregistered until the next restart. Two changes fix it fail-safe:
 *  - **[guardJson]** classifies every response BEFORE deserializing: a 2xx `application/json` is the only real
 *    HubChallenge/result; anything else surfaces the concrete status + content-type + a bounded body prefix
 *    (diagnostic, not cryptic), tagged **retryable** (transport error / 5xx / non-JSON edge warmup) vs **terminal**
 *    (a 4xx the CP itself rendered — auth, which never self-heals → fail fast).
 *  - **[retry]** a BOUNDED exponential backoff over the whole round-trip; each attempt re-fetches a fresh challenge
 *    (the nonce is single-use). The dial leg already had [com.tneff.cyppieagents.net.hub.remote.RemoteHubSession]
 *    backoff; admit was the only leg without. The structural [readyGate] in BootOrchestrator removes the race at the
 *    root; the retry covers the residual (edge upstream warmup) — belt and suspenders.
 */
class HubAdmissionClient(
    private val cpBaseUrl: String,
    private val http: HttpClient,
    private val registrar: ControlPlaneRegistrar,
    private val operatorBearer: () -> String?,
    private val retry: AdmissionRetry = AdmissionRetry(),
    /** Injectable so tests drive backoff deterministically (no real sleeping); prod = [kotlinx.coroutines.delay]. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /**
     * Run the round-trip with bounded retry. A TRANSIENT failure (transport error / 5xx / non-JSON edge response)
     * is retried up to [AdmissionRetry.maxAttempts]; a TERMINAL rejection ([AdmissionRejectedException], a 4xx the
     * CP itself served — auth) is NOT retried (it won't self-heal) and propagates with its diagnostic message; a
     * returned [HubAdmissionResult] (even `admitted=false`, e.g. owner_mismatch) means the CP answered → return it.
     * Retries exhausted → a terminal `cp_unreachable_after_retries` result (never a silent success).
     */
    suspend fun admit(): HubAdmissionResult =
        cpRetry( // CYP-526: the retry loop is now the shared [cpRetry] (single-sourced with the rendezvous register)
            retry, sleep,
            onExhausted = { HubAdmissionResult(admitted = false, reason = "cp_unreachable_after_retries: ${it.message}") },
        ) { attemptAdmit() } // each attempt re-fetches a fresh challenge (the nonce is single-use; never reuse a stale one)

    private suspend fun attemptAdmit(): HubAdmissionResult {
        val bearer = operatorBearer()?.takeIf { it.isNotBlank() }
            ?: return HubAdmissionResult(admitted = false, reason = "no_operator_credential")
        val challengeResp = cpRequest("challenge") {
            http.get("$cpBaseUrl/api/cp/challenge") { header("Authorization", "Bearer $bearer") }
        }
        cpJsonGuard(challengeResp, "challenge") // ★ never blind-deserialize a non-JSON edge response
        val challenge = challengeResp.body<HubChallenge>()
        val nonce = Base64.getDecoder().decode(challenge.nonce)
        val reg = registrar.register(nonce) // build the signed registration (transcript PoP over the CP-issued nonce)
        val admitResp = cpRequest("admit") {
            http.post("$cpBaseUrl/api/cp/admit") {
                header("Authorization", "Bearer $bearer")
                contentType(ContentType.Application.Json)
                setBody(HubAdmissionRequest(reg, challenge.nonce))
            }
        }
        cpJsonGuard(admitResp, "admit")
        return admitResp.body()
    }
}

/** CYP-524 — bounded exponential backoff for boot admission (never an unbounded loop). Single-sourced defaults. */
data class AdmissionRetry(
    val maxAttempts: Int = 6,
    val baseDelayMs: Long = 250,
    val maxDelayMs: Long = 4_000,
    val factor: Double = 2.0,
) {
    /** Delay before the NEXT attempt (1-based): exponential from [baseDelayMs], capped at [maxDelayMs]. */
    fun delayForAttempt(attempt: Int): Long {
        val exp = baseDelayMs.toDouble() * Math.pow(factor, (attempt - 1).coerceAtLeast(0).toDouble())
        return exp.toLong().coerceIn(0, maxDelayMs)
    }
}

/** CYP-524 — a transient admission-transport failure (edge not up, 5xx, non-JSON warmup) — retryable in the boot window. */
class AdmissionTransientException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** CYP-524 — a definitive CP-served rejection (a 4xx the CP itself rendered as JSON — auth) — NOT retryable; fail fast. */
class AdmissionRejectedException(message: String) : Exception(message)

/**
 * CYP-512 — the env-gated **boot factory** (PO GO env-A, fail-closed → INERT, mirror of `buildRemoteTransport`).
 * Returns a boot-time admission runnable ONLY when `CYPPIE_CP_URL` + `CYPPIE_CP_OPERATOR_TOKEN` (the hub's operator
 * bearer for the CP — custody like the other `CYPPIE_CP_*`/`CYPPIE_MASTER_KEY` secrets) + `CYPPIE_OPERATOR_ID` (the
 * `ownerId` the hub claims, which the CP re-checks equals the authenticated operator) are ALL present AND local-hub
 * custody exists; else `null` (INERT — the hub does not self-admit). The boot CALLS this once. `httpClientFactory` /
 * `env` are injectable for tests.
 *
 * ★ Custody note (into the flip-authorization package): a compromised hub would hold operator power on the CP as this
 * operator — MVP-acceptable because the hub + operator are the SAME trust domain (the operator runs their own hub);
 * `operator-drives-admission` (central-UI admit, no hub-held operator secret) is the future BYOA ticket, not MVP.
 */
fun buildHubAdmission(
    hubIdentity: HubIdentity?,
    hubSecretStore: SecretStore?,
    hubIdentityFile: java.io.File?,
    hubName: String,
    hubPort: Int,
    env: (String) -> String? = System::getenv,
    httpClientFactory: () -> HttpClient = { HttpClient(CIO) { install(ContentNegotiation) { json(CommJson) } } },
): (suspend () -> HubAdmissionResult)? {
    val cpUrl = env("CYPPIE_CP_URL")?.takeIf { it.isNotBlank() } ?: return null
    val opToken = env("CYPPIE_CP_OPERATOR_TOKEN")?.takeIf { it.isNotBlank() } ?: return null
    val ownerId = env("CYPPIE_OPERATOR_ID")?.takeIf { it.isNotBlank() } ?: return null
    if (hubIdentity == null || hubSecretStore == null || hubIdentityFile == null) return null
    val provisioner = HubIdentityProvisioner(hubSecretStore, hubIdentityFile.toPath())
    val registrar = ControlPlaneRegistrar(hubIdentity, provisioner, NoOpControlPlaneConnector, ownerId, hubName, hubPort)
    val client = HubAdmissionClient(cpUrl, httpClientFactory(), registrar, { opToken })
    return { client.admit() }
}
