package com.tneff.cyppieagents.support

import com.tneff.cyppieagents.connector.Connector
import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.ProviderInfo
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Reusable connector test-double with caller-chosen tri-state [Capabilities] and **no real process**
 * (CYP-121 harness lane; CYP-124-C2 Tester reuses it). Booted through the CYP-120 `connectorFactory`
 * seam, it lets a test exercise Mediator capability-gating + the `capability.degraded` event-log path
 * without a live `claude` — Connector A is all-AVAILABLE, so gating is a no-op on it.
 *
 * Records the agentIds it [open]ed so a test can assert the platform actually used the injected connector.
 */
class FakeConnector(override val capabilities: Capabilities) : Connector {
    override val provider: ProviderInfo = ProviderInfo.CLAUDE
    val opened = mutableListOf<String>()

    override fun open(agentId: String): ConnectorSession {
        opened += agentId
        return FakeSession(agentId)
    }

    private class FakeSession(override val agentId: String) : ConnectorSession {
        override val events: Flow<StreamJsonEvent> = emptyFlow()
        override suspend fun sendTurn(turn: UserTurn) {}
        override fun close() {}
    }

    companion object {
        /** A connector with every dimension at [status] and the given [kind] — handy for gating tests. */
        fun uniform(status: CapabilityStatus, kind: ConnectorKind = ConnectorKind.MCP) = FakeConnector(
            Capabilities(status, status, status, status, status, kind),
        )
    }
}
