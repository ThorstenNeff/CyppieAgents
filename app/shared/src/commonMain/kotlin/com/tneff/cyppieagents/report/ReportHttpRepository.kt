package com.tneff.cyppieagents.report

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.GenerateReportRequest
import com.tneff.cyppieagents.model.ReportMeta
import com.tneff.cyppieagents.model.ReportSnapshot
import com.tneff.cyppieagents.model.ReportType
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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/**
 * Live REST [ReportRepository] against the CYP-89 report endpoints (PRODUCT-LEAD §2), decoding the shared
 * `:core` wire DTOs ([ReportMeta]/[ReportSnapshot]/[GenerateReportRequest]) through the shared [CommJson]
 * so the contract can't drift. The CYP-90 **stub→real swap**: replaces `StubReportRepository` as the
 * default with **no UI/VM change**.
 *
 * Operator-gated/fail-closed is preserved: every call carries the operator token; without one the server
 * 401/403s and the VM's load fails closed (no list, no report — never a partial). The items are
 * content-free **by structure** — the client only decodes what the server (CYP-89) enforces. A non-2xx
 * maps to a [ReportException] with the server's reason code (`invalid_report_type`/`report_not_found`/
 * `operator_required`/`unauthorized`) from the `{error:{code,message}}` envelope.
 */
class ReportHttpRepository(
    private val client: HttpClient,
    private val baseUrl: String,
    private val token: String,
) : ReportRepository {

    override suspend fun list(): List<ReportMeta> =
        getDecoded("/api/reports", ListSerializer(ReportMeta.serializer()))

    override suspend fun get(id: String): ReportSnapshot =
        getDecoded("/api/reports/$id", ReportSnapshot.serializer())

    override suspend fun generate(type: ReportType): ReportSnapshot {
        val response = client.post("$baseUrl/api/reports") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(CommJson.encodeToString(GenerateReportRequest.serializer(), GenerateReportRequest(type)))
        }
        val text = response.bodyAsText()
        ensureSuccess(response, text)
        return CommJson.decodeFromString(ReportSnapshot.serializer(), text)
    }

    private suspend fun <T> getDecoded(path: String, serializer: KSerializer<T>): T {
        val response = client.get("$baseUrl$path") { header(HttpHeaders.Authorization, "Bearer $token") }
        val text = response.bodyAsText()
        ensureSuccess(response, text)
        return CommJson.decodeFromString(serializer, text)
    }

    /** Map a non-2xx to the server's reason code (the `{error:{code,message}}` envelope), else by status. */
    private fun ensureSuccess(response: HttpResponse, text: String) {
        if (response.status.isSuccess()) return
        val code = runCatching { CommJson.decodeFromString(ApiErrorBody.serializer(), text).error.code }
            .getOrElse {
                when (response.status.value) {
                    401 -> "unauthorized"
                    403 -> "operator_required"
                    404 -> "report_not_found"
                    else -> "report_error"
                }
            }
        throw ReportException(code)
    }
}
