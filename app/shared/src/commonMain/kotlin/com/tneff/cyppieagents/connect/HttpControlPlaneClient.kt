package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.CommJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import com.tneff.cyppieagents.model.HubDescriptor as CoreHubDescriptor

/**
 * S-J — the live [ControlPlaneClient] that replaces [StubControlPlaneClient] (the CYP-419 stub was spec'd "the live
 * HTTP client lands in S-J"). Same-origin CP over the operator **session** Bearer, mirroring the ratified CP-HTTP
 * pattern of [com.tneff.cyppieagents.net.hub.operator.HttpCpJwtProvider] (CYP-496) and
 * [com.tneff.cyppieagents.net.hub.relay.HttpRendezvousResolver] (CYP-494):
 * `GET {cpBaseUrl}/api/cp/hubs` → the operator's hubs, projected by the CP from its owner↔hub mapping (the hub
 * self-admits via CYP-427 challenge/admit; the desktop only **lists + selects**).
 *
 * **Fail-closed (H5):** no operator session, a non-200 (401/403 structural), or any transport error ⇒
 * [ControlPlaneUnreachableException] — an honest, surfaced error, never a silent hang and never a fabricated
 * "online" hub. The wire is the `:core` [CoreHubDescriptor] (CYP-481, incl. the base64 `dhPubKey` the client
 * TOFU-pins), mapped straight-through to the client-facing [HubDescriptor] (identical fields).
 */
class HttpControlPlaneClient(
    private val client: HttpClient,
    private val cpBaseUrl: String,
    private val operatorToken: suspend () -> String?,
) : ControlPlaneClient {

    override suspend fun hubs(): List<HubDescriptor> {
        // Operator-gated: the hub list reveals the account's hub↔registry mapping. No session ⇒ fail closed (H5).
        val token = operatorToken() ?: throw ControlPlaneUnreachableException("no operator session (fail-closed)")
        val response = try {
            client.get("$cpBaseUrl/api/cp/hubs") { header(HttpHeaders.Authorization, "Bearer $token") }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            // A transport error (CP unreachable / DNS / refused) is an HONEST unreachable, never a hang or a
            // fabricated list — surfaces the H5 hub-list error state, no cache-faked "online".
            throw ControlPlaneUnreachableException()
        }
        if (response.status != HttpStatusCode.OK) {
            throw ControlPlaneUnreachableException("CP /hubs returned ${response.status} (structural) — fail-closed")
        }
        return CommJson.decodeFromString(ListSerializer(CoreHubDescriptor.serializer()), response.bodyAsText())
            .map { it.toClient() }
    }

    /**
     * S-J (PO decision, pending Backend confirmation) — the model is **list + select**: the hub self-admits to the
     * CP (CYP-427 challenge/admit is server→CP), so a desktop-operator "register" is vestigial (no register route,
     * no register DTO). Fail-closed honest error (surfaces the A2 register-offline copy), never a fabricated hub.
     * Revisit iff Backend confirms an operator-facing register endpoint is required.
     */
    override suspend fun registerHub(name: String): HubRegistration =
        throw ControlPlaneUnreachableException("hub registration is CP self-admit (list+select) — no desktop register")
}

/** Map the `:core` wire [CoreHubDescriptor] (CYP-481) straight-through to the client-facing [HubDescriptor].
 *  CYP-802: [CoreHubDescriptor.issuerTrust] (CYP-804, axis c) is threaded through — the client-produce edge derives
 *  `IssuerNotTrusted` from it; dropping it here would leave the produce edge inert (the Cyp802 toClient tooth guards). */
private fun CoreHubDescriptor.toClient(): HubDescriptor =
    HubDescriptor(
        hubId = hubId,
        name = name,
        online = online,
        defaultPort = defaultPort,
        lastSeen = lastSeen,
        dhPubKey = dhPubKey,
        issuerTrust = issuerTrust,
    )
