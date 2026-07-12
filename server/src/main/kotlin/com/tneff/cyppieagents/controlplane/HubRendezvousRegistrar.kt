package com.tneff.cyppieagents.controlplane

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post

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
) {
    suspend fun register(): String? {
        val bearer = operatorBearer()?.takeIf { it.isNotBlank() } ?: return null
        val resp = http.post("$cpBaseUrl/api/cp/rendezvous/$hubId") { header("Authorization", "Bearer $bearer") }
            .body<RendezvousResolveResponse>()
        return resp.binding?.rendezvousId
    }
}
