package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.connector.ClaudeCodeConnector
import com.tneff.cyppieagents.connector.Connector
import com.tneff.cyppieagents.mediation.MediationRouter
import com.tneff.cyppieagents.mediation.SessionRegistry
import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-120: Connector A (stream-json) MUST declare its capabilities honestly (Doc 10 §3/§4 column A) —
 * all five dimensions AVAILABLE + kind STREAM_JSON. Mutation proof: flip any single dimension to
 * LIMITED/UNAVAILABLE (or change the kind) in the production declaration → this test reddens.
 *
 * Read through the [Connector] interface type, so it also proves the contract obligation
 * (`val capabilities`) is actually satisfied by the concrete connector, not just a stray constant.
 */
class ClaudeCodeConnectorCapabilitiesTest {

    private fun newConnector(): Connector {
        val agents = listOf(
            Agent("po", "PO", Role.PO, "po"),
            Agent("backend", "BE", Role.WORKER, "backend"),
        )
        val hub = Hub(HubState.hubAndSpoke(agents), InMemoryMessageStore())
        val registry = SessionRegistry()
        // No spawn happens in this test — capabilities is a static declaration, independent of I/O.
        return ClaudeCodeConnector(
            spawner = { _, _, _ -> error("no spawn in capability test") },
            worktreesRoot = { File("/tmp/cyp120-cap-test") },
            resolveApiKey = { null },
            registry = registry,
            router = MediationRouter(registry, hub),
            turnQueue = SessionTurnQueue(),
            scope = CoroutineScope(Job()),
        )
    }

    @Test
    fun connectorADeclaresAllDimensionsAvailableAsStreamJson() {
        val caps = newConnector().capabilities
        assertEquals(CapabilityStatus.AVAILABLE, caps.structuredUsage, "structuredUsage ← ResultEvent.usage")
        assertEquals(CapabilityStatus.AVAILABLE, caps.toolGranularity, "toolGranularity ← tool_use/tool_result blocks")
        assertEquals(CapabilityStatus.AVAILABLE, caps.reliableResult, "reliableResult ← ResultEvent turn-end")
        assertEquals(CapabilityStatus.AVAILABLE, caps.rateLimitSignal, "rateLimitSignal ← RateLimitEvent.rate_limit_info")
        assertEquals(CapabilityStatus.AVAILABLE, caps.coordination, "coordination ← mediation (stream-read + stdin inject)")
        assertEquals(ConnectorKind.STREAM_JSON, caps.kind)
    }

    @Test
    fun capabilitiesMatchesThePublishedCompanionConstant() {
        // The companion is the single source the boot/read path will resolve from (CYP-122).
        assertEquals(ClaudeCodeConnector.STREAM_JSON_CAPABILITIES, newConnector().capabilities)
    }
}
