package com.tneff.cyppieagents.connector

/**
 * Write-seam for an operator's connector choice on an **existing** agent (CYP-123/CYP-126, spec §3 —
 * Connector-Auswahl + Opt-in B). The live [ConnectorSelectionHttpRepository] hits the dedicated, audited
 * `POST /api/agents/{id}/connector` endpoint (CYP-122); the **server is authoritative** and re-checks the
 * operator gate, re-verifies the B acknowledgment, and audits B activations (spec §3.3, fail-closed).
 *
 * [agentId] is **nullable** only to keep the contract uniform; the edit/opt-in path always has a concrete id
 * (the live impl throws `agent_required` on null). The **add** case does NOT reach this port — the create
 * carries the connector on `NewAgentSpec.connectorKind` (no connector-endpoint call), so an add picker captures
 * the kind into the add-form rather than calling [activate].
 *
 * Security (spec §3.3/§5, load-bearing): there is **no** path that activates a connector from message/agent
 * input — activation is exclusively the operator's deliberate UI act ([ConnectorSelectionViewModel.selectKind]
 * for A, [ConnectorSelectionViewModel.confirmOptIn] for the ack-gated B). This port is only ever reached from
 * those two operator-gated entry points.
 */
interface ConnectorSelectionRepository {
    suspend fun activate(agentId: String?, selection: ConnectorSelection)
}

/**
 * In-memory write stub for dev/tests. Records each activation; mirrors the server's fail-closed re-checks so
 * the UI behaves identically before the real swap:
 *  - [denyWrites] (when set) throws a [ConnectorException] with that code (e.g. `operator_required`), modelling
 *    the server gate rejecting the write.
 *  - an unacknowledged B ([ConnectorSelection] MCP without `riskAcknowledged`) throws `risk_ack_required` —
 *    the server re-verifies the B ack (the UI gate is not trusted alone).
 */
class StubConnectorSelectionRepository(
    private val denyWrites: String? = null,
) : ConnectorSelectionRepository {
    /** Recorded (agentId, selection) pairs, newest last — for tests to assert exactly-one B activation etc. */
    val activations = mutableListOf<Pair<String?, ConnectorSelection>>()

    override suspend fun activate(agentId: String?, selection: ConnectorSelection) {
        denyWrites?.let { throw ConnectorException(it) }
        if (!selection.isAcknowledgmentSatisfied) throw ConnectorException("risk_ack_required")
        activations.add(agentId to selection)
    }
}
