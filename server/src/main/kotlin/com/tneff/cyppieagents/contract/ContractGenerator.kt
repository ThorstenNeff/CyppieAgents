package com.tneff.cyppieagents.contract

import com.tneff.cyppieagents.model.AgentBusyStateEvent
import com.tneff.cyppieagents.model.AgentTerminalControlEvent
import com.tneff.cyppieagents.model.AgentRunStateEvent
import com.tneff.cyppieagents.model.AgentTokenUsageEvent
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.CommWsClientEvent
import com.tneff.cyppieagents.model.CommWsServerEvent
import com.tneff.cyppieagents.model.EventsWsClientEvent
import com.tneff.cyppieagents.model.EventsWsServerEvent
import com.tneff.cyppieagents.model.HubDescriptorValidity
import com.tneff.cyppieagents.model.HubTrustState
import com.tneff.cyppieagents.model.StoredAgentEvent
import com.tneff.cyppieagents.model.TrustRejectReason
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.TerminalClientFrame
import com.tneff.cyppieagents.model.TerminalServerFrame
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * CYP-234a-2a — assembles the neutral WS contract as an **AsyncAPI 2.6** document, GENERATED from `:core` (the
 * same [SchemaWalker] the fidelity engine proves), so a Go/Godot/Web/CLI frontend can read the WS protocol
 * without reading Kotlin. Only the **FRONTEND** WS channels are here; `/ws/hub` (the remote-AGENT wire) is
 * DELIBERATELY excluded → it belongs to the connector contract (design §2.5), and the drift-test asserts it is
 * a KNOWN exclusion, not silently missing. Direction is stated explicitly per operation (`server → client` /
 * `client → server`) so the AsyncAPI-2.x publish/subscribe ambiguity can't mislead a builder.
 */
object ContractGenerator {

    /** A frontend WS channel: its path + the server→client (subscribe) and optional client→server (publish) roots. */
    data class WsChannel(val path: String, val serverToClient: SerialDescriptor, val clientToServer: SerialDescriptor?)

    /** The FRONTEND WS surface (design §2.5 excludes `/ws/hub`). `/ws/agent` is token-only (design §2.4). */
    val WS_CHANNELS: List<WsChannel> = listOf(
        WsChannel("/ws/comm", serializer<CommWsServerEvent>().descriptor, serializer<CommWsClientEvent>().descriptor),
        WsChannel("/ws/events", serializer<EventsWsServerEvent>().descriptor, serializer<EventsWsClientEvent>().descriptor),
        WsChannel("/ws/lifecycle", serializer<AgentRunStateEvent>().descriptor, null),
        WsChannel("/ws/token-usage", serializer<AgentTokenUsageEvent>().descriptor, null), // CYP-316: one-way token feed
        WsChannel("/ws/busy-state", serializer<AgentBusyStateEvent>().descriptor, null), // CYP-324: one-way busy/idle feed
        WsChannel("/ws/terminal-state", serializer<AgentTerminalControlEvent>().descriptor, null), // CYP-354 (BE-1): one-way terminal-control-mode feed
        // CYP-409 P1 fix: the server→client frame is the FULL StoredAgentEvent{seq,agentId,projectId,tsMs,event},
        // NOT the bare inner StreamJsonEvent — verified at AgentSocket.kt (the pump sends
        // `CommJson.encodeToString(StoredAgentEvent.serializer(), stored)`) and matched by AgentWsClient. The
        // declared-bare `StreamJsonEvent` dropped `seq`, on which the `?since` replay + seq-idempotency depend.
        WsChannel("/ws/agent", serializer<StoredAgentEvent>().descriptor, serializer<UserTurn>().descriptor),
        // CYP-332: bidirectional PTY-over-WS terminal transport (Base64 byte frames + resize/exit).
        WsChannel("/ws/terminal", serializer<TerminalServerFrame>().descriptor, serializer<TerminalClientFrame>().descriptor),
    )

    /** WS paths intentionally NOT in the frontend AsyncAPI (asserted by the drift-test as known exclusions). */
    val EXCLUDED_WS_PATHS: Set<String> = setOf("/ws/hub")

    /** Build the AsyncAPI 2.6 document (channels + component messages + generated schemas) from `:core`. */
    fun asyncApi(): JsonObject {
        val walker = SchemaWalker()
        WS_CHANNELS.forEach { ch -> walker.schemaFor(ch.serverToClient); ch.clientToServer?.let { walker.schemaFor(it) } }
        val messageRoots = WS_CHANNELS.flatMap { listOfNotNull(it.serverToClient, it.clientToServer) }
            .distinctBy { walker.schemaName(it) }

        return buildJsonObject {
            put("asyncapi", "2.6.0")
            putJsonObject("info") {
                put("title", "Cyppie Agents — WebSocket API")
                put("version", "1.0.0")
                put("description", "Frontend WS channels. Generated from :core (kotlinx.serialization) — accurate by construction. /ws/hub is the remote-agent wire (connector contract), not a frontend transport.")
            }
            putJsonObject("channels") {
                WS_CHANNELS.forEach { ch ->
                    putJsonObject(ch.path) {
                        putJsonObject("subscribe") {
                            put("description", "server → client (the client subscribes to receive)")
                            putJsonObject("message") { put("\$ref", "#/components/messages/${walker.schemaName(ch.serverToClient)}") }
                        }
                        ch.clientToServer?.let { p ->
                            putJsonObject("publish") {
                                put("description", "client → server (the client publishes to send)")
                                putJsonObject("message") { put("\$ref", "#/components/messages/${walker.schemaName(p)}") }
                            }
                        }
                    }
                }
            }
            putJsonObject("components") {
                putJsonObject("messages") {
                    messageRoots.forEach { d ->
                        putJsonObject(walker.schemaName(d)) {
                            putJsonObject("payload") { put("\$ref", "#/components/schemas/${walker.schemaName(d)}") }
                        }
                    }
                }
                put("schemas", JsonObject(walker.components))
            }
        }
    }

    // ---- CYP-234a-2a: OpenAPI 3.1 (REST) — assembled from the hand-authored [RestContract], components
    // walker-generated, drift-tested against the live routing by RestContractDriftTest ----

    private fun bodyDescriptor(b: RestContract.Body): SerialDescriptor? = when (b) {
        is RestContract.Body.Json -> b.descriptor
        is RestContract.Body.JsonArray -> b.element
        else -> null
    }

    /** The JSON-Schema for a request/response body (a `$ref` for named types, resolved via [walker]). */
    private fun bodyContent(b: RestContract.Body, walker: SchemaWalker): JsonObject? = when (b) {
        RestContract.Body.None -> null
        RestContract.Body.Text -> jsonContent("text/plain", buildJsonObject { put("type", "string") })
        RestContract.Body.BinaryPng -> jsonContent("image/png", buildJsonObject { put("type", "string"); put("format", "binary") })
        RestContract.Body.Multipart -> jsonContent("multipart/form-data", buildJsonObject { put("type", "object") })
        is RestContract.Body.Json -> jsonContent("application/json", walker.schemaFor(b.descriptor))
        is RestContract.Body.JsonArray -> jsonContent("application/json", buildJsonObject {
            put("type", "array"); put("items", walker.schemaFor(b.element))
        })
    }

    private fun jsonContent(mediaType: String, schema: JsonObject): JsonObject = buildJsonObject {
        putJsonObject("content") { putJsonObject(mediaType) { put("schema", schema) } }
    }

    /** Auth: PUBLIC → no requirement; else either the bearer token OR the session cookie satisfies. The
     *  fine-grained authZ level (participant/operator/member) is documented per-op via `x-auth-tier`.
     *  [externalHosted] (234a-3 / PO-Assistant exposure-audit): the EXTERNAL hosted docs present **Bearer-only**
     *  — third-party BYO-frontend consumers authenticate via the participant Bearer token (ratified), not the
     *  first-party Kratos session cookie; publishing the cookie NAME in public docs is mild IdP fingerprinting
     *  with no value to that audience. NOT a contract change — the raw `/api` contract keeps BOTH paths; only
     *  the hosted render filters to Bearer. */
    private fun securityFor(tier: RestContract.Tier, externalHosted: Boolean): JsonArray = when (tier) {
        RestContract.Tier.PUBLIC -> JsonArray(emptyList())
        else -> buildJsonArray {
            add(buildJsonObject { putJsonArray("bearerAuth") {} })
            if (!externalHosted) add(buildJsonObject { putJsonArray("sessionCookie") {} })
        }
    }

    /**
     * CYP-798 — the standalone-EXPORTED hub-trust vocabulary enums (axis a, `:core/model/HubTrust.kt`). Emitted as
     * NAMED `components/schemas` in [openApi] via [SchemaWalker.registerNamedEnum], route-INDEPENDENTLY, so the
     * TS/browser team (Team-2) consumes them as named types (`contract:gen` merges openapi-only component names on
     * top of asyncapi) even though no REST route references them yet — the trust state is §5-a client-DERIVED and
     * does NOT cross the wire (verified: 0 DTO-field usages). PL-ratified Option A: a synthetic REST route would
     * pollute the REST contract AND break SILENTLY if someone removed the "unused" route. Adding a member here is
     * the deliberate, drift-guarded export knob (Cyp798StandaloneEnumExportTest pins presence + members).
     */
    val STANDALONE_ENUM_EXPORTS: List<SerialDescriptor> = listOf(
        serializer<HubTrustState>().descriptor,
        serializer<TrustRejectReason>().descriptor,
        serializer<HubDescriptorValidity>().descriptor,
    )

    /** The Bearer-only variant served on the EXTERNAL hosted docs surface (234a-3). Still GENERATED (no
     *  artifact) — a documented transform of [openApi], so single-source holds. */
    fun hostedOpenApi(): JsonObject = openApi(externalHosted = true)

    fun openApi(externalHosted: Boolean = false): JsonObject {
        val walker = SchemaWalker()
        val errorSchema = walker.schemaFor(serializer<ApiErrorBody>().descriptor)
        RestContract.REST_OPS.forEach { op ->
            bodyDescriptor(op.request)?.let { walker.schemaFor(it) }
            bodyDescriptor(op.response)?.let { walker.schemaFor(it) }
        }
        // CYP-798 — surface the standalone hub-trust vocabulary as named components (route-independent; see the list's
        // KDoc). SchemaWalker inlines enums at field sites, so a route-unreferenced vocabulary enum needs this knob.
        STANDALONE_ENUM_EXPORTS.forEach { walker.registerNamedEnum(it) }

        return buildJsonObject {
            put("openapi", "3.1.0")
            putJsonObject("info") {
                put("title", "Cyppie Agents — REST API")
                put("version", "1.0.0")
                put("description", "Frontend REST surface. Components generated from :core (kotlinx.serialization); paths hand-authored and drift-tested against the live routing. /mcp/hub (connector wire) is excluded.")
            }
            putJsonObject("paths") {
                RestContract.REST_OPS.groupBy { it.path }.forEach { (path, ops) ->
                    putJsonObject(path) {
                        ops.forEach { op ->
                            putJsonObject(op.method.lowercase()) {
                                put("operationId", op.method.lowercase() + path.replace(Regex("[/{}]"), "_"))
                                put("x-auth-tier", op.tier.name)
                                put("security", securityFor(op.tier, externalHosted))
                                bodyContent(op.request, walker)?.let { put("requestBody", it) }
                                putJsonObject("responses") {
                                    val success = if (op.response == RestContract.Body.None) "204" else "200"
                                    putJsonObject(success) {
                                        put("description", "success")
                                        bodyContent(op.response, walker)?.let { rb ->
                                            (rb["content"] as? JsonObject)?.let { put("content", it) }
                                        }
                                    }
                                    putJsonObject("4XX") {
                                        put("description", "error envelope")
                                        putJsonObject("content") { putJsonObject("application/json") { put("schema", errorSchema) } }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            putJsonObject("components") {
                put("schemas", JsonObject(walker.components))
                putJsonObject("securitySchemes") {
                    putJsonObject("bearerAuth") { put("type", "http"); put("scheme", "bearer") }
                    // 234a-3: the session-cookie scheme is OMITTED on the external hosted surface (Bearer-only).
                    if (!externalHosted) putJsonObject("sessionCookie") { put("type", "apiKey"); put("in", "cookie"); put("name", "ory_kratos_session") }
                }
            }
        }
    }
}
