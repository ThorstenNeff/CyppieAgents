package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer

/**
 * Read port for per-agent connector capabilities (CYP-123, spec §2). Capabilities ride the **Agent read-model**
 * (`GET /api/agents`, beside `runState`); an agent whose `Agent.capabilities` is `null` is **absent** from the
 * map ⇒ the UI shows "not yet reported" (fail-closed, never faked as full). Stub today; [ConnectorCapabilityHttpRepository]
 * at the swap. The connector **write** (selection/opt-in) is a separate seam, stubbed until CYP-122.
 */
interface ConnectorCapabilityRepository {
    suspend fun capabilities(): Map<String, Capabilities>
}

/** In-memory read stub for dev/tests. */
class StubConnectorCapabilityRepository(
    private val initial: Map<String, Capabilities> = emptyMap(),
) : ConnectorCapabilityRepository {
    override suspend fun capabilities(): Map<String, Capabilities> = initial
}

/**
 * Live read against the public `GET /api/agents` (secret-free, same source as the lifecycle snapshot). Maps each
 * `Agent.capabilities` (when non-null) into the per-agent map; a missing/undecodable response yields no caps —
 * honest, the UI stays "not yet reported" rather than inventing fidelity (fail-closed).
 */
class ConnectorCapabilityHttpRepository(
    private val client: HttpClient,
    private val httpBaseUrl: String,
) : ConnectorCapabilityRepository {
    override suspend fun capabilities(): Map<String, Capabilities> = try {
        val response = client.get("$httpBaseUrl/api/agents")
        if (!response.status.isSuccess()) {
            emptyMap()
        } else {
            CommJson.decodeFromString(ListSerializer(Agent.serializer()), response.bodyAsText())
                .mapNotNull { agent -> agent.capabilities?.let { agent.id to it } }
                .toMap()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        emptyMap()
    }
}

/**
 * The capability profile a connector *kind* declares (Doc 10 §4) — used for the B opt-in **preview** (showing
 * what B trades away before activation) and dev/test fixtures. Real per-agent values come from the server; this
 * is the documented A/B contract: A full fidelity; B = no token tracking, thinner tool/result/rate-limit, but
 * coordination GOOD via MCP tools.
 */
fun defaultCapabilitiesFor(kind: ConnectorKind): Capabilities = when (kind) {
    ConnectorKind.STREAM_JSON -> Capabilities(
        structuredUsage = CapabilityStatus.AVAILABLE,
        toolGranularity = CapabilityStatus.AVAILABLE,
        reliableResult = CapabilityStatus.AVAILABLE,
        rateLimitSignal = CapabilityStatus.AVAILABLE,
        coordination = CapabilityStatus.AVAILABLE,
        kind = ConnectorKind.STREAM_JSON,
    )
    ConnectorKind.MCP -> Capabilities(
        structuredUsage = CapabilityStatus.UNAVAILABLE,
        toolGranularity = CapabilityStatus.LIMITED,
        reliableResult = CapabilityStatus.LIMITED,
        rateLimitSignal = CapabilityStatus.LIMITED,
        coordination = CapabilityStatus.AVAILABLE,
        kind = ConnectorKind.MCP,
    )
}
