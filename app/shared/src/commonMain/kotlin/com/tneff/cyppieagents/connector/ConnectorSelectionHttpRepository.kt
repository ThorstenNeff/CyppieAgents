package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.ConnectorChoice
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * Live REST [ConnectorSelectionRepository] against the CYP-122 connector opt-in endpoint
 * (`POST /api/agents/{id}/connector`, server `routing/ConnectorRoutes.kt`). The CYP-123 write-seam
 * **stub→real swap**: replaces [StubConnectorSelectionRepository] as the default with **no UI/VM change**.
 *
 * The body is the dedicated, audited `:core` [ConnectorChoice] (`{ "connectorKind": "mcp"|"stream_json" }`),
 * encoded through the shared [CommJson] so the contract can't drift. The connector is changed **only** through
 * this dedicated operator-gated route — never via the general agent edit ([com.tneff.cyppieagents.model.AgentEdit]
 * deliberately omits `connectorKind`), so a connector change is always an explicit, logged decision (anti-injection).
 *
 * **Operator-gated, fail-closed (server re-checks):** the server runs `requireOperator` BEFORE receiving the body
 * (401/403), 404 `agent_not_found` for an unknown agent, and on success persists + re-declares caps + emits the
 * `connector.optin` audit event. A non-2xx maps to a [ConnectorException] carrying the server's reason code from
 * the `{error:{code,message}}` envelope. Success is [Unit] — the active connector is the server truth, re-fetched
 * separately by the per-agent capability display (Increment 1), never claimed here.
 *
 * [agentId] must be non-null on this path (the edit/opt-in always targets a concrete agent; the add case carries
 * the kind in `NewAgentSpec` and never reaches here). A defensive `null` throws `agent_required` (fail-closed).
 */
class ConnectorSelectionHttpRepository(
    private val client: HttpClient,
    private val baseUrl: String,
    private val operatorToken: String,
) : ConnectorSelectionRepository {

    override suspend fun activate(agentId: String?, selection: ConnectorSelection) {
        val id = agentId ?: throw ConnectorException("agent_required") // defensive: this path always has an id
        val response = client.post("$baseUrl/api/agents/$id/connector") {
            header(HttpHeaders.Authorization, "Bearer $operatorToken")
            contentType(ContentType.Application.Json)
            setBody(CommJson.encodeToString(ConnectorChoice.serializer(), ConnectorChoice(selection.kind)))
        }
        if (response.status.isSuccess()) return // success = Unit; capability display re-fetches the active truth
        val text = response.bodyAsText()
        val code = runCatching { CommJson.decodeFromString(ApiErrorBody.serializer(), text).error.code }
            .getOrElse {
                when (response.status.value) {
                    401 -> "unauthorized"
                    403 -> "operator_required"
                    404 -> "agent_not_found"
                    else -> "connector_error"
                }
            }
        throw ConnectorException(code)
    }
}
