package com.tneff.cyppieagents.controlplane

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

/**
 * CYP-521 (Epic CYP-427 Phase-2, activation) — the hub-side **rendezvous register** client: the register-pendant of
 * the client-side resolve (Dev CYP-494 / CYP-511). It POSTs `/api/cp/rendezvous/{hubId}` (owner-gated — the hub must
 * be admitted + owned via CYP-512 first) and returns the **CURRENT epoch-derived rendezvous id**. The hub then dials
 * the relay with EXACTLY this id, so it pairs with a client resolving the same id.
 *
 * ★ This closes the flip-blocker: the hub-dial side previously read a **static** `CYPPIE_REMOTE_RENDEZVOUS` env id,
 * which can NEVER match `LiveRelayRendezvous.register`'s per-registration epoch id → no pairing at the relay. Now the
 * hub obtains the id the same way the client resolves it — from the CP, at connect time.
 *
 * Operator-authenticated (`CYPPIE_CP_OPERATOR_TOKEN`, custody like the CYP-512 admit bearer). `null` = fail-closed
 * (registration denied / relay INERT / no operator credential) → the dialer does not dial.
 */
class HubRendezvousRegistrar(
    private val cpBaseUrl: String,
    private val http: HttpClient,
    private val hubId: String,
    private val operatorBearer: () -> String?,
    /** CYP-526 — the register POST hits the hub's OWN edge (like admit), so it shares the boot-window resilience:
     *  bounded retry + the [cpJsonGuard] (no blind deserialize of a non-JSON edge response). Single-sourced [cpRetry]. */
    private val retry: AdmissionRetry = AdmissionRetry(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val log = LoggerFactory.getLogger("cp.rendezvous.register")

    /**
     * Register with the CP → the CURRENT epoch-derived rendezvous id. Retry-resilient + diagnostic (CYP-526). Returns
     * `null` fail-closed: no operator credential, exhausted transient retries, or a CP-served terminal rejection (e.g.
     * the owner-gate before the hub is admitted). The reason is logged; the caller ([WebSocketRelayDialer]) fails the
     * dial closed and the reconnect loop backs off + retries. **The caller caches the id — register mints a FRESH
     * epoch every call (unlinkability, CYP-501 §2), so re-registering per re-dial would rotate the id and strand the
     * client's resolved id.** Register ONCE; reuse the cached id on reconnect.
     */
    suspend fun register(): String? = registerBinding()?.rendezvousId

    /**
     * CYP-536 (M2 Option A, WS1) — the **N-set** register: returns the full epoch-derived rendezvous set
     * (`RendezvousBinding.rendezvousIds`) the hub runs its N concurrent responders over. Same POST + resilience as
     * [register]; only the projection differs (the whole set vs element 0). `null`/empty fail-closed. Backs the
     * [com.tneff.cyppieagents.transport.CachingRendezvousIdSet] → the `SessionRendezvousSource` seam.
     */
    suspend fun registerSet(): List<String>? = registerBinding()?.rendezvousIds?.takeIf { it.isNotEmpty() }

    /** The shared register POST → the CP [RendezvousBinding] (retry-resilient, JSON-guarded), or `null` fail-closed. */
    private suspend fun registerBinding(): RendezvousBinding? {
        val bearer = operatorBearer()?.takeIf { it.isNotBlank() } ?: return null
        return try {
            cpRetry(retry, sleep, onExhausted = { log.warn("CYP-526 rendezvous register failed after retries: {}", it.message); null }) {
                val resp = cpRequest("rendezvous") {
                    http.post("$cpBaseUrl/api/cp/rendezvous/$hubId") { header("Authorization", "Bearer $bearer") }
                }
                cpJsonGuard(resp, "rendezvous") // never blind-deserialize a non-JSON edge response (the CYP-524 class)
                resp.body<RendezvousResolveResponse>().binding
            }
        } catch (t: AdmissionRejectedException) {
            log.warn("CYP-526 rendezvous register rejected (terminal): {}", t.message)
            null
        }
    }
}
