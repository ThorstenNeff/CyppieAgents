package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.StreamJsonEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-316 — the live context-token feed (`onContextTokens`) the projector derives from a `ResultEvent.usage`,
 * gated by the SAME `structuredUsage` decision as banding (single-sourced). Faithful: the value is derived
 * from the REAL usage JSON via `UsageSnapshot`, not a hardcoded literal.
 *
 * contextTokens = `input + cache_read + cache_creation` — **output excluded** (not standing context).
 *
 * Mutations that red these:
 *  - include `output_tokens` in the sum → the exact-value assertion reds;
 *  - relax the AVAILABLE-only gate (emit B's coarse count) → the Connector-B→null assertion reds.
 */
class EventProjectorTokenUsageTest {

    private fun caps(usage: CapabilityStatus) = Capabilities(
        structuredUsage = usage,
        toolGranularity = CapabilityStatus.AVAILABLE,
        reliableResult = CapabilityStatus.AVAILABLE,
        rateLimitSignal = CapabilityStatus.AVAILABLE,
        coordination = CapabilityStatus.AVAILABLE,
        kind = ConnectorKind.MCP,
    )

    // The context fields sum to 12000 + 3000 + 500 = 15500; output_tokens (9999) is DELIBERATELY not counted.
    private val result = CommJson.decodeFromString<StreamJsonEvent>(
        """{"type":"result","subtype":"success","is_error":false,"session_id":"s","uuid":"u",
           "usage":{"input_tokens":12000,"cache_read_input_tokens":3000,"cache_creation_input_tokens":500,"output_tokens":9999}}""",
    )
    private val EXPECTED = 12000 + 3000 + 500 // 15500 — output excluded

    /** Run the projector with the given capability resolver over [result], returning the pushed token values. */
    private fun pushes(resolve: ((String) -> Capabilities?)?): List<Int?> {
        val out = ArrayList<Int?>()
        EventProjector(
            ContextUsageBander(),
            projectId = "t",
            capabilities = resolve,
            onContextTokens = { _, tokens -> out.add(tokens) },
        ).project("backend", "s", "c", result)
        return out
    }

    @Test fun connectorA_available_pushesExactContextTokens_outputExcluded() =
        assertEquals(listOf<Int?>(EXPECTED), pushes { caps(CapabilityStatus.AVAILABLE) })

    @Test fun noResolver_legacy_pushesTheNumber() =
        assertEquals(listOf<Int?>(EXPECTED), pushes(null))

    @Test fun connectorB_limited_pushesNull_notTheCoarseCount() =
        assertEquals(listOf<Int?>(null), pushes { caps(CapabilityStatus.LIMITED) })

    @Test fun structuredUsageUnavailable_pushesNull() =
        assertEquals(listOf<Int?>(null), pushes { caps(CapabilityStatus.UNAVAILABLE) })

    @Test fun registryMiss_failsClosed_pushesNull() =
        assertEquals(listOf<Int?>(null), pushes { null })
}
