package com.tneff.cyppieagents.connector

/**
 * Write-seam for an operator's connector choice (CYP-123, spec §3 — Connector-Auswahl + Opt-in B). Stubbed
 * until CYP-122: the real persistence rides the **agent-spec** (`NewAgentSpec`/`AgentEdit` — the CYP-86 line),
 * not a parallel store; the **server is authoritative** and re-checks the operator gate, re-verifies the B
 * acknowledgment, and audits B activations (spec §3.3, fail-closed). [agentId] is **nullable** for the add
 * dialog (no id yet — the choice is captured; the real binding lands at CYP-122).
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
