package com.tneff.cyppieagents.net.hub.relay

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.controlplane.RendezvousResolveResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart

/**
 * CYP-494 — the live [RendezvousResolver]: `GET {cpBaseUrl}/api/cp/rendezvous/{hubId}` (operator-gated), decoding
 * the CYP-507 `:core` [RendezvousResolveResponse] via [CommJson] and mapping it fail-closed ([toResolution]).
 *
 * **Business outcomes are a 200 + typed body** (the CYP-507 idiom): a `binding` or a typed `failure` both come
 * back 200 and are parsed here. Only **structural** failures are HTTP errors — a non-200 (401/403 auth, or any
 * transport error the client raises) is a resolve *failure of the CP call itself* → thrown, so the dialer's
 * caller surfaces it as `RelayUnreachable` (transient), never a fabricated `Bound`. The operator bearer is
 * required (the resolve reveals the hubId↔rendezvous mapping); a missing token fails closed the same way.
 */
class HttpRendezvousResolver(
    private val client: HttpClient,
    private val cpBaseUrl: String,
    private val operatorToken: suspend () -> String?,
) : RendezvousResolver {

    override suspend fun resolve(hubId: String): RendezvousResolution {
        val token = operatorToken() ?: error("no operator token — CP rendezvous resolve is operator-gated (fail-closed)")
        val response = client.get("$cpBaseUrl/api/cp/rendezvous/${hubId.encodeURLPathPart()}") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (response.status != HttpStatusCode.OK) {
            error("CP rendezvous resolve returned ${response.status} (structural failure) — fail-closed")
        }
        return CommJson.decodeFromString(RendezvousResolveResponse.serializer(), response.bodyAsText()).toResolution()
    }
}
