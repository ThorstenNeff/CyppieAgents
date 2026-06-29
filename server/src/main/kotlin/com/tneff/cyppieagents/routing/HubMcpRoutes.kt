package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.connector.HubMcpConfigWriter
import com.tneff.cyppieagents.connector.HubMcpTools
import com.tneff.cyppieagents.mediation.HubSendArgs
import com.tneff.cyppieagents.model.MessageMeta
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * CYP-146 / E1.7 — the **in-process Hub MCP server** (`POST /mcp/hub`). It gives a Connector-A
 * (stream-json) agent a callable `hub_send` tool (surfaced by the CLI as `mcp__hub__hub_send`), closing
 * the RB1 emission gap: the PO can now actually delegate. A minimal MCP/JSON-RPC surface — `initialize`,
 * `tools/list`, `tools/call` — over HTTP, handled in-JVM so the tool routes straight to [Hub.postAsAgent].
 *
 * **Trust boundary (E2.4 / F3):**
 *  - **Every call is authenticated** — the bearer token resolves to an agentId server-side
 *    ([TokenRegistry.agentFor]); no unauthenticated path, and an agent **cannot claim another agentId**
 *    (the token *is* the identity; operator/unknown tokens are rejected — only agents emit).
 *  - **Single write path:** `tools/call hub_send` routes **only** through [HubMcpTools.send] →
 *    [Hub.postAsAgent] (canWrite-recompute / `from`+`projectId` server-stamp / mask / size-cap). No
 *    second write path; the agent-supplied args carry **no** `from`/`projectId`/right (Gate #1).
 *  - **Localhost-bound:** mounted on the localhost-bound server ([bootHost] = 127.0.0.1); the mcp-config
 *    points at `http://127.0.0.1:<port>/mcp/hub` (F1/F3), and the token lives only in the 0600 out-of-repo
 *    config ([HubMcpConfigWriter]).
 *  - **Malformed args → fail-closed:** nothing is posted (mirrors CYP-131 [HubSendArgs.parse]).
 */
fun Route.hubMcpRoutes(hub: Hub, registry: TokenRegistry) {
    post("/mcp/hub") {
        // Auth FIRST, fail-closed: only an agent token (→ its own agentId) may emit. No agent → 401.
        val agentId = registry.agentFor(call.bearerToken())
        if (agentId == null) {
            call.respond(HttpStatusCode.Unauthorized, jsonRpcError(JsonNull, -32001, "unauthorized"))
            return@post
        }

        val req = runCatching { call.receive<JsonObject>() }.getOrNull()
        if (req == null) {
            call.respond(HttpStatusCode.BadRequest, jsonRpcError(JsonNull, -32700, "parse error"))
            return@post
        }
        val id: JsonElement = req["id"] ?: JsonNull
        when (val method = req["method"]?.jsonPrimitive?.content) {
            "initialize" -> call.respond(jsonRpcResult(id, initializeResult(req)))
            // Notifications carry no id → no response body (ack only).
            "notifications/initialized", "notifications/cancelled" -> call.respond(HttpStatusCode.Accepted)
            "tools/list" -> call.respond(jsonRpcResult(id, toolsListResult()))
            "tools/call" -> call.respond(handleToolsCall(hub, agentId, id, req))
            else -> call.respond(jsonRpcError(id, -32601, "method not found: $method"))
        }
    }
}

/** `tools/call` → route ONLY `hub_send` through the Hub chokepoint; everything else fail-closed. */
private suspend fun handleToolsCall(hub: Hub, agentId: String, id: JsonElement, req: JsonObject): JsonObject {
    val params = req["params"]?.jsonObject
    val name = params?.get("name")?.jsonPrimitive?.content
    if (name != HubMcpConfigWriter.SEND_TOOL) {
        return jsonRpcError(id, -32602, "unknown tool: $name")
    }
    val args = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())
    val cmd = HubSendArgs.parse(args)
        ?: return jsonRpcResult(id, toolErrorContent("hub_send: missing/invalid 'channel' or 'text'")) // fail-closed
    return try {
        // SINGLE write path: identity = the authenticated agentId (NEVER an arg); canWrite + stamps in postAsAgent.
        val posted = HubMcpTools(hub, agentId).send(cmd.channel, cmd.text, cmd.kind?.let { MessageMeta(kind = it) })
        jsonRpcResult(id, toolTextContent("sent to ${cmd.channel} (id ${posted.id})"))
    } catch (e: ForbiddenException) {
        jsonRpcResult(id, toolErrorContent("forbidden: no write access to '${cmd.channel}'")) // 403 → tool error, not a crash
    }
}

// ---- MCP/JSON-RPC shapes ----

private fun jsonRpcResult(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    put("result", result)
}

private fun jsonRpcError(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    putJsonObject("error") { put("code", code); put("message", message) }
}

/** Echo the client's requested protocolVersion when present (max compatibility), else our default. */
private fun initializeResult(req: JsonObject): JsonObject {
    val clientVersion = req["params"]?.jsonObject?.get("protocolVersion")?.jsonPrimitive?.content
    return buildJsonObject {
        put("protocolVersion", clientVersion ?: "2025-06-18")
        putJsonObject("capabilities") { putJsonObject("tools") {} }
        putJsonObject("serverInfo") { put("name", HubMcpConfigWriter.SERVER_NAME); put("version", "1.0.0") }
    }
}

private fun toolsListResult(): JsonObject = buildJsonObject {
    putJsonArray("tools") {
        add(
            buildJsonObject {
                put("name", HubMcpConfigWriter.SEND_TOOL)
                put("description", "Send a message to a hub channel on your behalf (delegate a task / report status).")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("channel") { put("type", "string"); put("description", "target channel id, e.g. po-backend") }
                        putJsonObject("text") { put("type", "string"); put("description", "message body") }
                        putJsonObject("kind") { put("type", "string"); put("description", "optional: TASK | STATUS | NOTE") }
                    }
                    putJsonArray("required") { add(JsonPrimitive("channel")); add(JsonPrimitive("text")) }
                }
            },
        )
    }
}

private fun toolTextContent(text: String): JsonObject = buildJsonObject {
    putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", text) }) }
    put("isError", false)
}

private fun toolErrorContent(text: String): JsonObject = buildJsonObject {
    putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", text) }) }
    put("isError", true)
}
