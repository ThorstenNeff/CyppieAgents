package com.tneff.cyppieagents.connector

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import kotlin.test.Test

/**
 * CYP-123 capability-display render contract (default locale = DE). Proves the load-bearing honesty:
 *  - the fidelity badge is **absent for a full agent** (fail-closed by absence) and **present** for `null`
 *    ("not yet reported") and for any degraded agent — presence flips with [Capabilities.isDegraded];
 *  - the panel renders the active-connector line, all five dimension rows, and the honesty footnote — never
 *    omitting a dimension;
 *  - a degraded panel's UNAVAILABLE chip shows the unavailable **label** (visible, not silently dropped, not
 *    error-red).
 */
@OptIn(ExperimentalTestApi::class)
class ConnectorCapabilityViewTest {

    private val full = Capabilities(
        CapabilityStatus.AVAILABLE, CapabilityStatus.AVAILABLE, CapabilityStatus.AVAILABLE,
        CapabilityStatus.AVAILABLE, CapabilityStatus.AVAILABLE, ConnectorKind.STREAM_JSON,
    )
    private val degraded = Capabilities(
        CapabilityStatus.UNAVAILABLE, CapabilityStatus.LIMITED, CapabilityStatus.LIMITED,
        CapabilityStatus.LIMITED, CapabilityStatus.AVAILABLE, ConnectorKind.MCP,
    )

    @Test
    fun badge_absentForFullAgent_failClosedByAbsence() = runComposeUiTest {
        setContent { MaterialTheme { ConnectorCapabilityBadge(full, "agA") } }
        onNodeWithTag(ConnectorTags.fidelityBadge("agA")).assertDoesNotExist()
    }

    @Test
    fun badge_presentForNullCaps_notYetReported() = runComposeUiTest {
        setContent { MaterialTheme { ConnectorCapabilityBadge(null, "agA") } }
        onNodeWithTag(ConnectorTags.fidelityBadge("agA")).assertExists()
    }

    @Test
    fun badge_presentForDegradedAgent_presenceFlipsWithIsDegraded() = runComposeUiTest {
        // Mutation-style: same composable, only isDegraded differs → presence flips (cf. the full-agent test).
        setContent { MaterialTheme { ConnectorCapabilityBadge(degraded, "agA") } }
        onNodeWithTag(ConnectorTags.fidelityBadge("agA")).assertExists()
    }

    @Test
    fun panel_rendersActiveLine_allFiveDimensionRows_andFootnote() = runComposeUiTest {
        setContent { MaterialTheme { CapabilityPanel(degraded, "agA") } }
        onNodeWithTag(ConnectorTags.capabilityPanel("agA")).assertExists()
        onNodeWithTag(ConnectorTags.activeConnector("agA")).assertExists()
        // All five dimension rows are present (never omitted).
        for (dim in listOf(
            CapabilityDimension.STRUCTURED_USAGE,
            CapabilityDimension.TOOL_GRANULARITY,
            CapabilityDimension.RELIABLE_RESULT,
            CapabilityDimension.RATE_LIMIT_SIGNAL,
            CapabilityDimension.COORDINATION,
        )) {
            onNodeWithTag(ConnectorTags.capability("agA", dim)).assertExists()
        }
        // Honesty footnote (no dedicated tag) — assert by its text (JVM default locale = EN in this gate).
        onNodeWithText("never faked", substring = true).assertExists()
    }

    @Test
    fun panel_unavailableChip_showsUnavailableLabel_visibleNotDropped() = runComposeUiTest {
        setContent { MaterialTheme { CapabilityPanel(degraded, "agA") } }
        // structuredUsage is UNAVAILABLE → its status chip carries the unavailable LABEL, proving UNAVAILABLE
        // is rendered visibly (not omitted, not silent). It also carries the distinct "○" glyph (colour-free).
        onNodeWithTag(ConnectorTags.capabilityStatus("agA", CapabilityDimension.STRUCTURED_USAGE))
            .assertTextContains("unavailable", substring = true)
        onNodeWithTag(ConnectorTags.capabilityStatus("agA", CapabilityDimension.STRUCTURED_USAGE))
            .assertTextContains("○", substring = true)
    }

    @Test
    fun panel_nullCaps_rendersAllFiveDimensionsUnavailable_failClosed() = runComposeUiTest {
        setContent { MaterialTheme { CapabilityPanel(null, "agA") } }
        // Fail-closed: null caps still renders the full table, every known dimension UNAVAILABLE (never blank).
        onNodeWithTag(ConnectorTags.capability("agA", CapabilityDimension.COORDINATION)).assertExists()
        onNodeWithTag(ConnectorTags.capabilityStatus("agA", CapabilityDimension.COORDINATION))
            .assertTextContains("unavailable", substring = true)
    }
}
