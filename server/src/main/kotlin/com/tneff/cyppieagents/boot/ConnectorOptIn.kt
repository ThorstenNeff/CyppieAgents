package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.connector.CapabilityRegistry
import com.tneff.cyppieagents.events.EventDraft
import com.tneff.cyppieagents.events.EventRecorder
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The single, server-enforced point that sets an agent's connector (CYP-122 / Doc 10 §3,§5). A connector
 * choice — especially opting into Connector B (lower fidelity, the documented account/billing risk) — is a
 * deliberate decision, so [apply] does three things atomically from the caller's view:
 *  1. persists the choice on the agent config ([AgentConfigRegistry.setConnectorKind]; effective next spawn),
 *  2. re-declares the agent's capabilities in the [CapabilityRegistry] so the Mediator gating + `GET
 *     /api/agents` reflect it immediately (no restart needed for the read model), and
 *  3. **audits** it as a content-free `connector.optin` event (the risk acknowledgment, never silent).
 *
 * It is reached ONLY through the operator-gated opt-in route and agent-create — never a channel message
 * (no "off-message" path). The opt-in event is the deliberate re-declare hook the boot caps-emit defers to.
 */
class ConnectorOptIn(
    // CYP-255 (.4b): resolved through the ACTIVE project's runtime (were boot-pinned singletons). An opt-in
    // sets the connector on the ACTIVE project's agent config + re-declares its caps in the ACTIVE project's
    // registry — a same-id agent in another project is untouched.
    private val agentConfigs: () -> AgentConfigRegistry,
    private val capabilityRegistry: () -> CapabilityRegistry,
    private val eventRecorder: EventRecorder,
    private val projectId: () -> String,
) {
    fun apply(agentId: String, kind: ConnectorKind) {
        agentConfigs().setConnectorKind(agentId, kind)
        capabilityRegistry().set(agentId, ConnectorRouter.capabilitiesForKind(kind))
        eventRecorder.record(
            EventDraft(
                agentId = agentId,
                projectId = projectId(),
                type = EventType.CONNECTOR_OPTIN,
                severity = Severity.WARN,
                detail = buildJsonObject {
                    put("agentId", agentId)
                    put("connectorKind", kind.name) // content-free: which connector, no secrets
                },
            ),
        )
    }
}
