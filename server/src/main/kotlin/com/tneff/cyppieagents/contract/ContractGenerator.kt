package com.tneff.cyppieagents.contract

import com.tneff.cyppieagents.model.AgentRunStateEvent
import com.tneff.cyppieagents.model.CommWsClientEvent
import com.tneff.cyppieagents.model.CommWsServerEvent
import com.tneff.cyppieagents.model.EventsWsClientEvent
import com.tneff.cyppieagents.model.EventsWsServerEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        WsChannel("/ws/agent", serializer<StreamJsonEvent>().descriptor, serializer<UserTurn>().descriptor),
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
}
