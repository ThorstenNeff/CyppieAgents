package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.comm.CommHttpException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * CYP-738 — the **narrow client seam** for the caller's OWN writable-**agent** set (the agent-side sibling of
 * comm's [com.tneff.cyppieagents.comm.WritableChannelsApi]).
 *
 * Backing endpoint (Backend, agreed contract — separate post-window ticket):
 * ```
 * GET /api/agents/writable → { "agentIds": string[] }   // agent ids the resolved caller may message RIGHT NOW
 * ```
 * Auth: operator/session (same gate as `/api/channels/writable`); no request body. The server 403/chokepoint
 * (CYP-188, `AclReducer`) stays the real enforcement point — this set only lets the agent composer disable
 * **honestly** (comfort/honesty, not the enforce point).
 *
 * The shape (`{ agentIds }`) is FIXED (the agreed answer to the CYP-738 measured need — the agent send is
 * channel-id-less client-side, so no `agentId→channelId` mapping exists to consult the channel `writable` set),
 * so the client binding is built + tested against it now; wiring the real HTTP implementation once the endpoint
 * lands is a one-line shell swap (`?: HttpAgentWritableApi(...)`). **Until the seam is injected the feature is
 * dormant** (the composer keeps its pre-CYP-738 unconditional-editable path — never disable prod messaging before
 * a real signal exists). When active, an error / not-yet-known result maps to `UNKNOWN` → disabled-with-hint,
 * **never** silently editable (the §4a leak the PL guardrail forbids).
 */
fun interface AgentWritableApi {
    /** The agent ids the resolved caller may message right now. */
    suspend fun writableAgents(): List<String>
}

/**
 * CYP-738 (wiring, ready for the post-window swap) — the live HTTP implementation: `GET {baseUrl}/api/agents/writable`
 * → `{ agentIds: [...] }`, bearer-authed like [com.tneff.cyppieagents.comm.HttpWritableChannelsApi] (same
 * participant/operator gate server-side). A non-2xx (401/404-pre-deploy/500) throws [CommHttpException] → the VM
 * maps it to `UNKNOWN` (disabled), never optimistically editable. Token-agnostic: the caller injects the bearer.
 *
 * `:app:shared` carries **no** kotlinx.serialization compiler plugin (see `ConfigRepository` — DTOs live in `:core`),
 * so the body is navigated at runtime via the JSON tree (like comm's builtin-serializer decode), not a
 * `@Serializable` DTO. A shape that isn't `{ agentIds: [...] }` decodes to the empty set (fail-safe, not a throw).
 */
class HttpAgentWritableApi(
    private val client: HttpClient,
    private val baseUrl: String,
    private val token: String,
) : AgentWritableApi {
    override suspend fun writableAgents(): List<String> {
        val response = client.get("$baseUrl/api/agents/writable") { header(HttpHeaders.Authorization, "Bearer $token") }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw CommHttpException(response.status.value, text)
        val root = CommJson.parseToJsonElement(text) as? JsonObject ?: return emptyList()
        val ids = root["agentIds"] as? JsonArray ?: return emptyList()
        return ids.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }
}

/** A scriptable [AgentWritableApi] for building + toothing the tri-state without a live endpoint. */
class StubAgentWritableApi(private val agentIds: List<String>) : AgentWritableApi {
    override suspend fun writableAgents(): List<String> = agentIds
}
