package com.tneff.cyppieagents.crossproject

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.AuthorizeShareRequest
import com.tneff.cyppieagents.model.ChannelShareView
import com.tneff.cyppieagents.model.ShareAccess
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * Live REST [CrossProjectRepository] against the CYP-93 `/api/channels/{id}/share` endpoints, decoding the
 * shared `:core` wire DTOs ([ChannelShareView]/[com.tneff.cyppieagents.model.ReachedAgent]/[ShareAccess]/
 * [AuthorizeShareRequest]) through the shared [CommJson] so the contract can't drift. The **stub→real swap**
 * (pattern CYP-85/90): replaces `StubCrossProjectRepository` with **no UI/VM change** — the repo abstraction
 * maps `:core` [ChannelShareView] → the UI's [CrossShareStatus]/[CrossMember].
 *
 * - `GET` is participant-readable disclosure (the badge/status is not a secret).
 * - `PUT`/`DELETE` are operator/owner-gated (anti-injection §2.6; the server enforces too, fail-closed).
 *
 * **`sharedWith` derivation (flagged to PO):** the single-owner `authorize()` is parameterless, but the PUT
 * needs the grantee projects. We send [granteeProjects] = the operator's OTHER projects — the actual reach
 * stays gated **per-agent** by the channel's explicit ACL (no over-widen; the server computes the concrete
 * reach). The pre-share scope preview is empty until authorized (GET returns no reach pre-share, by contract).
 */
class HttpCrossProjectRepository(
    private val client: HttpClient,
    private val baseUrl: String,
    private val token: String,
    private val granteeProjects: () -> Set<String>,
) : CrossProjectRepository {

    override suspend fun status(channelId: String): CrossShareStatus {
        val response = client.get("$baseUrl/api/channels/$channelId/share") { auth() }
        return decode(channelId, response)
    }

    override suspend fun authorize(channelId: String): CrossShareStatus {
        val response = client.put("$baseUrl/api/channels/$channelId/share") {
            auth(); contentType(ContentType.Application.Json)
            setBody(CommJson.encodeToString(AuthorizeShareRequest.serializer(), AuthorizeShareRequest(granteeProjects())))
        }
        return decode(channelId, response)
    }

    override suspend fun revoke(channelId: String): CrossShareStatus {
        val response = client.delete("$baseUrl/api/channels/$channelId/share") { auth() }
        return decode(channelId, response)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private suspend fun decode(channelId: String, response: HttpResponse): CrossShareStatus {
        val text = response.bodyAsText()
        ensureSuccess(response, text)
        val view = CommJson.decodeFromString(ChannelShareView.serializer(), text)
        return CrossShareStatus(
            channelId = channelId,
            shared = view.shared,
            sharedAt = view.sharedAt,
            reachableMembers = view.reachableScope.map { r ->
                CrossMember(r.agentId, r.projectId, if (r.access == ShareAccess.WRITE) CrossAccess.WRITE else CrossAccess.READ)
            },
        )
    }

    /** Map a non-2xx to the server's reason code (the `{error:{code,message}}` envelope), else by status. */
    private fun ensureSuccess(response: HttpResponse, text: String) {
        if (response.status.isSuccess()) return
        val code = runCatching { CommJson.decodeFromString(ApiErrorBody.serializer(), text).error.code }
            .getOrElse {
                when (response.status.value) {
                    401 -> "unauthorized"
                    403 -> "operator_required"
                    else -> "crossproject_error"
                }
            }
        throw CrossProjectException(code)
    }
}
