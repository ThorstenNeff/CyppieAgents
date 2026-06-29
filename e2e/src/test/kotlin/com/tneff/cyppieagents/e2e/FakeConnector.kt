package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.connector.Connector
import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * E2E-local connector double (CYP-124). The server-test `FakeConnector` lives in `:server`'s **test**
 * sourceset, which is not on the `:e2e` classpath (`:e2e` depends on `:server` MAIN only) — so this is a
 * minimal mirror, not a duplicate of production code.
 *
 * It declares arbitrary [capabilities] and opens a **no-op** session (empty event flow, no real `claude`),
 * which is exactly enough to drive the boot-time capability declaration + `capability.degraded` emission
 * (BootOrchestrator, Doc 10 §3 / CYP-121) through the REAL embedded server. A journey then reads the
 * result over the real `/api/events` egress — proving the gating is visible end-to-end, not just in an
 * in-process sink.
 */
class FakeConnector(override val capabilities: Capabilities) : Connector {
    override fun open(agentId: String): ConnectorSession = FakeSession(agentId)

    private class FakeSession(override val agentId: String) : ConnectorSession {
        override val events: Flow<StreamJsonEvent> = emptyFlow()
        override suspend fun sendTurn(turn: UserTurn) {}
        override fun close() {}
    }

    companion object {
        /** All five capability dimensions at one [status]; [kind] defaults to MCP (the reduced connector). */
        fun uniform(status: CapabilityStatus, kind: ConnectorKind = ConnectorKind.MCP): FakeConnector =
            FakeConnector(Capabilities(status, status, status, status, status, kind))
    }
}
