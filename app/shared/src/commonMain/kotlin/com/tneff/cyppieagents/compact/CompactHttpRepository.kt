package com.tneff.cyppieagents.compact

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.CompactConfig
import com.tneff.cyppieagents.model.CompactStatus
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * Live REST [CompactRepository] against Backend's Milestone-C compact endpoints (§4), decoding the shared
 * `:core` DTOs ([CompactStatus]/[CompactConfig]) through the shared [CommJson] so the contract can't drift.
 * The CYP-326 **stub→real swap**: replaces [StubCompactRepository] as the default with no UI/VM change.
 *
 * `GET /api/compact/status` (read-tier) · `POST /api/compact/config` (operator-gated). A non-2xx maps to a
 * [CompactException] with the server's reason code (`operator_required` / `unauthorized`), so the VM's
 * honest server-mirror behaviour (no optimistic flip) runs unchanged.
 */
class CompactHttpRepository(
    private val client: HttpClient,
    private val baseUrl: String,
    private val token: String,
) : CompactRepository {

    override suspend fun getStatus(): CompactStatus {
        val response = client.get("$baseUrl/api/compact/status") { header(HttpHeaders.Authorization, "Bearer $token") }
        val text = response.bodyAsText()
        ensureSuccess(response, text)
        return CommJson.decodeFromString(CompactStatus.serializer(), text)
    }

    override suspend fun setConfig(config: CompactConfig): CompactStatus {
        val response = client.post("$baseUrl/api/compact/config") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(CommJson.encodeToString(CompactConfig.serializer(), config))
        }
        val text = response.bodyAsText()
        ensureSuccess(response, text)
        return CommJson.decodeFromString(CompactStatus.serializer(), text)
    }

    /** Map a non-2xx to the server's reason code (the `{error:{code,message}}` envelope), else by status. */
    private fun ensureSuccess(response: HttpResponse, text: String) {
        if (response.status.isSuccess()) return
        val code = runCatching { CommJson.decodeFromString(ApiErrorBody.serializer(), text).error.code }
            .getOrElse {
                when (response.status.value) {
                    401 -> "unauthorized"
                    403 -> "operator_required"
                    else -> "compact_error"
                }
            }
        throw CompactException(code)
    }
}
