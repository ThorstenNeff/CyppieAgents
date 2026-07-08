package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.StreamJsonEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-121: the projector gates event-log depth by connector capability (Doc 10 §3). toolGranularity not
 * AVAILABLE → no tool.call/tool.result; structuredUsage not AVAILABLE → no context.usage. Mutation: drop
 * a gate in [EventProjector.project] → the gated draft reappears under reduced caps → these go red.
 */
class EventProjectorCapabilityGateTest {

    private fun caps(tool: CapabilityStatus, usage: CapabilityStatus) = Capabilities(
        structuredUsage = usage,
        toolGranularity = tool,
        reliableResult = CapabilityStatus.AVAILABLE,
        rateLimitSignal = CapabilityStatus.AVAILABLE,
        coordination = CapabilityStatus.AVAILABLE,
        kind = ConnectorKind.MCP,
    )

    // Band math here is over an EXPLICIT 200k window (the percentages in the fixtures below assume it); this
    // test verifies banding LOGIC, not the production default, so pin it — isolated from the CYP-325 200k→1M
    // default correction (the real 1M default is asserted in Cyp325TokenContextSizeTest).
    private fun projector(resolve: ((String) -> Capabilities?)?) =
        EventProjector(ContextUsageBander(contextWindowTokens = 200_000L), projectId = "t", capabilities = resolve)

    private val toolUse = CommJson.decodeFromString<StreamJsonEvent>(
        """{"type":"assistant","session_id":"s","message":{"role":"assistant",
           "content":[{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"x"}}]}}""",
    )
    private val toolResult = CommJson.decodeFromString<StreamJsonEvent>(
        """{"type":"user","session_id":"s","message":{"role":"user",
           "content":[{"tool_use_id":"toolu_1","type":"tool_result","content":"hi","is_error":false}]}}""",
    )

    // contextTokens = 50000 / 200k window = 25% → band 20 crossing → a CONTEXT_USAGE draft on AVAILABLE.
    private val resultWithUsage = CommJson.decodeFromString<StreamJsonEvent>(
        """{"type":"result","subtype":"success","is_error":false,"session_id":"s","uuid":"u",
           "usage":{"input_tokens":50000}}""",
    )

    // contextTokens = 160000 / 200k = 80% → crosses the compact threshold (75) → a COMPACT CONTEXT_USAGE.
    private val resultPastCompact = CommJson.decodeFromString<StreamJsonEvent>(
        """{"type":"result","subtype":"success","is_error":false,"session_id":"s","uuid":"u",
           "usage":{"input_tokens":160000}}""",
    )

    private fun types(resolve: ((String) -> Capabilities?)?, e: StreamJsonEvent) =
        projector(resolve).project("backend", "s", "c", e).map { it.type }

    @Test
    fun allAvailableEmitsToolAndUsage_control() {
        val ok = caps(CapabilityStatus.AVAILABLE, CapabilityStatus.AVAILABLE)
        assertTrue(EventType.TOOL_CALL in types({ ok }, toolUse))
        assertTrue(EventType.TOOL_RESULT in types({ ok }, toolResult))
        assertTrue(EventType.CONTEXT_USAGE in types({ ok }, resultWithUsage))
    }

    @Test
    fun noResolverEmitsToolAndUsage_legacy() {
        // No capability system configured (null resolver) → no gating, so every pre-CYP-121 install/test
        // behaves exactly as before.
        assertTrue(EventType.TOOL_CALL in types(null, toolUse))
        assertTrue(EventType.CONTEXT_USAGE in types(null, resultWithUsage))
    }

    @Test
    fun toolGranularityDegradedStillEmitsToolEvents() {
        // CYP-122: LIMITED → DEGRADED → tools still emit (B is just thinner), only OFF suppresses.
        val degraded = caps(CapabilityStatus.LIMITED, CapabilityStatus.AVAILABLE)
        assertTrue(EventType.TOOL_CALL in types({ degraded }, toolUse))
        assertTrue(EventType.TOOL_RESULT in types({ degraded }, toolResult))
    }

    @Test
    fun structuredUsageDegradedIsCoarse_compactOnly() {
        // CYP-122: LIMITED → DEGRADED → coarse: a fine band-crossing (25%) emits NOTHING, but a
        // compact-threshold crossing (80%) still emits the one important context.usage.
        val degraded = caps(CapabilityStatus.AVAILABLE, CapabilityStatus.LIMITED)
        assertEquals(emptyList(), types({ degraded }, resultWithUsage).filter { it == EventType.CONTEXT_USAGE })
        assertTrue(EventType.CONTEXT_USAGE in types({ degraded }, resultPastCompact))
    }

    @Test
    fun registryMissFailsClosed_suppressesGatedEvents() {
        // F2: capability system IS configured (non-null resolver) but the agent is unknown (returns null)
        // → fail-closed, NOT assumed AVAILABLE. tool.* and context.usage are suppressed.
        val miss: (String) -> Capabilities? = { null }
        assertEquals(emptyList(), types(miss, toolUse))
        assertTrue(EventType.CONTEXT_USAGE !in types(miss, resultWithUsage))
    }

    @Test
    fun toolGranularityUnavailableSuppressesToolEvents() {
        val noTool = caps(CapabilityStatus.UNAVAILABLE, CapabilityStatus.AVAILABLE)
        assertEquals(emptyList(), types({ noTool }, toolUse))
        assertEquals(emptyList(), types({ noTool }, toolResult))
        // …but usage still flows (only its own dimension gates it).
        assertTrue(EventType.CONTEXT_USAGE in types({ noTool }, resultWithUsage))
    }

    @Test
    fun structuredUsageUnavailableSuppressesContextUsage_butKeepsResultFinal() {
        val noUsage = caps(CapabilityStatus.AVAILABLE, CapabilityStatus.UNAVAILABLE)
        val out = types({ noUsage }, resultWithUsage)
        assertTrue(EventType.RESULT_FINAL in out, "result.final is not usage-gated")
        assertTrue(EventType.CONTEXT_USAGE !in out, "context.usage suppressed when structuredUsage unavailable")
        // tool events still flow under this caps set.
        assertTrue(EventType.TOOL_CALL in types({ noUsage }, toolUse))
    }
}
