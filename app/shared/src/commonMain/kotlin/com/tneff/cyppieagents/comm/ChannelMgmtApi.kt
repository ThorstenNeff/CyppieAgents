package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.CreateChannelRequest
import com.tneff.cyppieagents.model.RenameChannelRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * CYP-883 (OS-C) — the operator channel-mutation seam the [ChannelManagementPanel] calls. Injected (render ≠
 * authority): the panel owns display + optimistic state + honest error surfacing, this port owns the actual
 * server round-trip. A failure throws [CommHttpException] (status-bearing) so the panel can map it to the honest
 * [ChannelMutationReason] (409 protected-HUB / 403 operator-only).
 */
interface ChannelMgmtApi {
    /** `POST /api/channels` — create a DIRECT/GROUP channel. Server-authoritative (rejects HUB / non-operator). */
    suspend fun create(req: CreateChannelRequest): Channel

    /** `PUT /api/channels/{id}` — rename (display-only; no ACL/topology impact, CYP-869). */
    suspend fun rename(channelId: String, name: String): Channel

    /** `DELETE /api/channels/{id}` — archive. Rejects a protected HUB with 409; non-operator with 403. */
    suspend fun archive(channelId: String)
}

/**
 * The live CYP-869 client: consumes `POST/PUT/DELETE /api/channels` directly. Token-agnostic (caller injects the
 * operator bearer). Non-2xx ⇒ [CommHttpException] with the status, so the panel surfaces 409/403 honestly.
 */
class HttpChannelMgmtApi(
    private val client: HttpClient,
    private val baseUrl: String,
    private val token: String,
) : ChannelMgmtApi {

    override suspend fun create(req: CreateChannelRequest): Channel {
        val response = client.post("$baseUrl/api/channels") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(CommJson.encodeToString(CreateChannelRequest.serializer(), req))
        }
        val text = response.bodyAsText()
        ensureSuccess(response, text)
        return CommJson.decodeFromString(Channel.serializer(), text)
    }

    override suspend fun rename(channelId: String, name: String): Channel {
        val response = client.put("$baseUrl/api/channels/$channelId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(CommJson.encodeToString(RenameChannelRequest.serializer(), RenameChannelRequest(name)))
        }
        val text = response.bodyAsText()
        ensureSuccess(response, text)
        return CommJson.decodeFromString(Channel.serializer(), text)
    }

    override suspend fun archive(channelId: String) {
        val response = client.delete("$baseUrl/api/channels/$channelId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        ensureSuccess(response, response.bodyAsText())
    }

    private fun ensureSuccess(response: HttpResponse, text: String) {
        if (!response.status.isSuccess()) throw CommHttpException(response.status.value, text)
    }
}
