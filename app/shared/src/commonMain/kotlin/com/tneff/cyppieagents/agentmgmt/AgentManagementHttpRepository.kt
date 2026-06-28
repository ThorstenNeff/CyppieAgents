package com.tneff.cyppieagents.agentmgmt

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentDetail
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.WorktreeFate
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/**
 * Live REST [AgentManagementRepository] against the CYP-97 agent-CRUD endpoints (AGENT-MANAGEMENT §2),
 * decoding the shared `:core` wire DTOs ([NewAgentSpec]/[AgentEdit]/[WorktreeFate]/[AgentDetail]) through
 * the shared [CommJson] so the contract can't drift. The CYP-86/87/88 **stub→real swap**: replaces
 * `StubAgentManagementRepository` as the default with **no UI/VM change**.
 *
 * Endpoints: `GET /api/agents` (list), `GET /api/agents/{id}` (CYP-101 edit-prefill detail), `POST`
 * (add → 201), `PUT /api/agents/{id}` (edit → 200), `DELETE /api/agents/{id}?worktree=keep|delete`
 * (→ 204). A non-2xx maps to an [AgentMgmtException] carrying the server's reason code from the
 * `{error:{code,message}}` envelope (`invalid_agent`/`agent_exists`/`po_already_exists`/`last_po`/
 * `agent_not_found`/`operator_required`/`unauthorized`), so the VM's honest disclosure runs unchanged.
 */
class AgentManagementHttpRepository(
    private val client: HttpClient,
    private val baseUrl: String,
    private val token: String,
) : AgentManagementRepository {

    override suspend fun list(): List<Agent> =
        getDecoded("/api/agents", ListSerializer(Agent.serializer()))

    override suspend fun detail(id: String): AgentDetail =
        getDecoded("/api/agents/$id", AgentDetail.serializer())

    override suspend fun add(spec: NewAgentSpec): Agent {
        val response = client.post("$baseUrl/api/agents") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(CommJson.encodeToString(NewAgentSpec.serializer(), spec))
        }
        val text = response.bodyAsText()
        ensureSuccess(response, text)
        return CommJson.decodeFromString(Agent.serializer(), text)
    }

    override suspend fun edit(id: String, edit: AgentEdit): Agent {
        val response = client.put("$baseUrl/api/agents/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(CommJson.encodeToString(AgentEdit.serializer(), edit))
        }
        val text = response.bodyAsText()
        ensureSuccess(response, text)
        return CommJson.decodeFromString(Agent.serializer(), text)
    }

    override suspend fun remove(id: String, worktree: WorktreeFate) {
        val response = client.delete("$baseUrl/api/agents/$id?worktree=${worktree.name.lowercase()}") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        ensureSuccess(response, response.bodyAsText()) // 204 No Content
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
                    404 -> "agent_not_found"
                    else -> "agent_mgmt_error"
                }
            }
        throw AgentMgmtException(code)
    }
}
