package com.tneff.cyppieagents.net.hub.operator

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.controlplane.HubTicketRequest
import com.tneff.cyppieagents.controlplane.HubTicketResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

/**
 * CYP-496 — the live [CpJwtProvider]: after the Noise handshake, requests the CP-minted hub ticket. It computes
 * the channel-binding `cb` via the injected [channelBinding] seam (the shared `:core` helper, CYP-514), POSTs
 * `HubTicketRequest{hubId, cb}` to `{cpBaseUrl}/api/cp/hubticket` with the operator **session** as Bearer
 * ([operatorToken] — same source as `authRepo::currentSessionToken`; the server accepts a verified Kratos
 * OPERATOR session, no separate token), and returns the minted `cpJwt`.
 *
 * **Fail-closed, never invents a ticket** — `null` on: no operator session; a non-200 (structural 401/403); or a
 * 200 with a typed failure (`CP_SESSION_EXPIRED` / `NOT_AUTHORIZED_FOR_HUB`). The auth path only needs
 * jwt-or-fail; the distinct operator-facing cause (re-auth vs terminal) is a separate UX surfacing (follow-up).
 */
class HttpCpJwtProvider(
    private val client: HttpClient,
    private val cpBaseUrl: String,
    private val operatorToken: suspend () -> String?,
    private val channelBinding: ChannelBinding,
) : CpJwtProvider {

    override suspend fun cpJwt(handshakeHash: ByteArray, hubId: String): String? {
        val token = operatorToken() ?: return null // no operator session ⇒ fail-closed (never anonymous)
        val cb = channelBinding.compute(handshakeHash, hubId)
        val requestBody = CommJson.encodeToString(HubTicketRequest.serializer(), HubTicketRequest(hubId = hubId, cb = cb))
        val response = client.post("$cpBaseUrl/api/cp/hubticket") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        if (response.status != HttpStatusCode.OK) return null // structural (401/403) ⇒ fail-closed
        // 200 + typed body: a cpJwt is a grant; a typed failure ⇒ cpJwt is null ⇒ we return null (fail-closed).
        return CommJson.decodeFromString(HubTicketResponse.serializer(), response.bodyAsText()).cpJwt
    }
}
