package com.tneff.cyppieagents.connector

/**
 * The connector/capability data port for CYP-123 (Doc 10). One seam, two responsibilities:
 *  - **read** the per-agent connector + capabilities for the steady-state display (badge + panel);
 *  - **write** an operator's connector choice (the deliberate opt-in act for [ConnectorKind.MCP]).
 *
 * Stub today ([StubConnectorRepository]); a later stub→real swap with NO UI/VM change (the S13/S17 pattern).
 * At real-swap:
 *  - [connectorInfos] reads the per-agent read-model (the Agents DTO, beside `runState` — CYP-120 locks the
 *    field placement);
 *  - [setConnector] rides the **agent-spec write** (CYP-86 line — NOT a parallel store). The server is the
 *    authority: it enforces the operator gate, re-checks the [ConnectorSelection] acknowledgment for B, and
 *    logs it as an audit event (CYP-122). The UI is only the affordance — a client-only gate would be the
 *    exact fail-open a reviewer catches (cf. CYP-49 server-side PO-lockout).
 */
interface ConnectorRepository {
    /** Per-agent connector info keyed by agentId. Fail-closed: a missing agent = no info (never a faked one). */
    suspend fun connectorInfos(): Map<String, AgentConnectorInfo>

    /** Operator-gated. Returns the updated [AgentConnectorInfo]. Throws [ConnectorException] on a server gate. */
    suspend fun setConnector(agentId: String, selection: ConnectorSelection): AgentConnectorInfo
}

/**
 * The capability profile a connector *kind* declares (Doc 10 §4). Real values come from the server at
 * real-swap; this stub models the documented A/B contract so the panel/badge demo the tri-state honestly.
 */
fun defaultCapabilitiesFor(kind: ConnectorKind): Capabilities = when (kind) {
    // A — stream-json (API): full fidelity, everything available.
    ConnectorKind.STREAM_JSON -> Capabilities(
        structuredUsage = CapabilityStatus.AVAILABLE,
        toolGranularity = CapabilityStatus.AVAILABLE,
        reliableResult = CapabilityStatus.AVAILABLE,
        rateLimitSignal = CapabilityStatus.AVAILABLE,
        coordination = CapabilityStatus.AVAILABLE,
    )
    // B — MCP (subscription): declared lower fidelity. Token tracking gone, others thinner, but coordination
    // is GOOD (structured MCP tool-calls, no scraping for the essential path) — Doc 10 §4.
    ConnectorKind.MCP -> Capabilities(
        structuredUsage = CapabilityStatus.UNAVAILABLE,
        toolGranularity = CapabilityStatus.LIMITED,
        reliableResult = CapabilityStatus.LIMITED,
        rateLimitSignal = CapabilityStatus.LIMITED,
        coordination = CapabilityStatus.AVAILABLE,
    )
}

/**
 * In-memory [ConnectorRepository] for dev/tests. [denyWrites] (e.g. `"operator_required"`) models the server
 * gate so tests can prove the UI fails closed; with it `null` the dev surface is writable. The write also
 * re-checks the MCP acknowledgment ([ConnectorSelection.isAcknowledgmentSatisfied]) — mirroring the server's
 * fail-closed re-check so a client that somehow skipped the ack is still rejected here.
 */
class StubConnectorRepository(
    initial: Map<String, AgentConnectorInfo> = emptyMap(),
    private val denyWrites: String? = null,
) : ConnectorRepository {

    private val infos: MutableMap<String, AgentConnectorInfo> = initial.toMutableMap()

    override suspend fun connectorInfos(): Map<String, AgentConnectorInfo> = infos.toMap()

    override suspend fun setConnector(agentId: String, selection: ConnectorSelection): AgentConnectorInfo {
        denyWrites?.let { throw ConnectorException(it) } // fail-closed: server operator gate
        if (!selection.isAcknowledgmentSatisfied) throw ConnectorException("risk_ack_required") // server re-check
        val updated = AgentConnectorInfo(agentId, selection.kind, defaultCapabilitiesFor(selection.kind))
        infos[agentId] = updated
        return updated
    }
}
