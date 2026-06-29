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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * CYP-132 test double (Tester ask A): a [ConnectorSession] that **records the bodies injected via
 * `sendTurn`** — the substrate the delivery ACs assert on (today's `FakeConnector.sendTurn` is a no-op).
 * No real process. Use directly in `:server` unit tests, or via [RecordingConnector] through the
 * `connectorFactory` seam in a boot/e2e test.
 */
class RecordingSession(override val agentId: String) : ConnectorSession {
    /** Injected [UserTurn.text]s, in delivery order. */
    val received = CopyOnWriteArrayList<String>()
    override val events: Flow<StreamJsonEvent> = emptyFlow()
    override suspend fun sendTurn(turn: UserTurn) { received.add(turn.text) }
    override fun close() {}
}

/**
 * A [Connector] whose sessions are [RecordingSession]s, retained by agentId so a test can inspect what
 * was delivered to each agent. Injected through the CYP-120 `connectorFactory` seam to drive the
 * production boot-wiring path (R2) with inspectable inbound.
 */
class RecordingConnector(
    override val capabilities: Capabilities = Capabilities(
        structuredUsage = CapabilityStatus.AVAILABLE,
        toolGranularity = CapabilityStatus.AVAILABLE,
        reliableResult = CapabilityStatus.AVAILABLE,
        rateLimitSignal = CapabilityStatus.AVAILABLE,
        coordination = CapabilityStatus.AVAILABLE,
        kind = ConnectorKind.STREAM_JSON,
    ),
) : Connector {
    override val provider: ProviderInfo = ProviderInfo.CLAUDE
    val sessions = ConcurrentHashMap<String, RecordingSession>()
    override fun open(agentId: String): ConnectorSession = sessions.getOrPut(agentId) { RecordingSession(agentId) }
}
