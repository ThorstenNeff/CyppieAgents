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
