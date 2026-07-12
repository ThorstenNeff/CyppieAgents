package com.tneff.cyppieagents.controlplane

import com.tneff.cyppieagents.boot.ControlPlaneRegistrar
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
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
 */
class HubAdmissionClient(
    private val cpBaseUrl: String,
    private val http: HttpClient,
    private val registrar: ControlPlaneRegistrar,
    private val operatorBearer: () -> String?,
) {
    suspend fun admit(): HubAdmissionResult {
        val bearer = operatorBearer()?.takeIf { it.isNotBlank() }
            ?: return HubAdmissionResult(admitted = false, reason = "no_operator_credential")
        val challenge = http.get("$cpBaseUrl/api/cp/challenge") { header("Authorization", "Bearer $bearer") }.body<HubChallenge>()
        val nonce = Base64.getDecoder().decode(challenge.nonce)
        val reg = registrar.register(nonce) // build the signed registration (transcript PoP over the CP-issued nonce)
        return http.post("$cpBaseUrl/api/cp/admit") {
            header("Authorization", "Bearer $bearer")
            contentType(ContentType.Application.Json)
            setBody(HubAdmissionRequest(reg, challenge.nonce))
        }.body()
    }
}
