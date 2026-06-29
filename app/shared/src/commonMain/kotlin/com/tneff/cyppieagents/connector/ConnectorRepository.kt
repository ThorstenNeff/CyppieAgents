package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.ProviderInfo
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer

/**
 * One consistent read snapshot of the per-agent connector read-model (CYP-123 capabilities + CYP-137 provider),
 * resolved from a **single** `GET /api/agents` so caps and provider are always the same server snapshot (never
 * two GETs that could disagree). Both maps are fail-closed by absence: an agent whose field is `null` is simply
 * **absent** from its map ⇒ the UI shows "not yet reported" (caps) / omits the qualifier (provider), never faked.
 */
data class ConnectorReadModel(
    val capabilities: Map<String, Capabilities> = emptyMap(),
    val providers: Map<String, ProviderInfo> = emptyMap(),
)

/**
 * Read port for the per-agent connector read-model (CYP-123 capabilities + CYP-137 provider). Both ride the
 * **Agent read-model** (`GET /api/agents`, beside `runState`); a `null` field ⇒ **absent** from its map ⇒
 * fail-closed display (never faked as full / never an invented provider). Stub today; [ConnectorCapabilityHttpRepository]
 * at the swap. The connector **write** (selection/opt-in) is a separate seam (operator-gated, CYP-122/126).
 */
interface ConnectorCapabilityRepository {
    suspend fun read(): ConnectorReadModel
}

/** In-memory read stub for dev/tests. Provider map defaults empty so existing caps-only call sites are unchanged. */
class StubConnectorCapabilityRepository(
    private val initial: Map<String, Capabilities> = emptyMap(),
    private val providers: Map<String, ProviderInfo> = emptyMap(),
) : ConnectorCapabilityRepository {
    override suspend fun read(): ConnectorReadModel = ConnectorReadModel(initial, providers)
}

/**
 * Live read against the public `GET /api/agents` (secret-free, same source as the lifecycle snapshot). One fetch
 * maps both `Agent.capabilities` and `Agent.provider` (each only when non-null) into their per-agent maps; a
 * missing/undecodable response yields an empty snapshot — honest, the UI stays "not yet reported" / omits the
 * provider rather than inventing either (fail-closed).
 */
class ConnectorCapabilityHttpRepository(
    private val client: HttpClient,
    private val httpBaseUrl: String,
) : ConnectorCapabilityRepository {
    override suspend fun read(): ConnectorReadModel = try {
        val response = client.get("$httpBaseUrl/api/agents")
        if (!response.status.isSuccess()) {
            ConnectorReadModel()
        } else {
            val agents = CommJson.decodeFromString(ListSerializer(Agent.serializer()), response.bodyAsText())
            ConnectorReadModel(
                capabilities = agents.mapNotNull { agent -> agent.capabilities?.let { agent.id to it } }.toMap(),
                providers = agents.mapNotNull { agent -> agent.provider?.let { agent.id to it } }.toMap(),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        ConnectorReadModel()
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
