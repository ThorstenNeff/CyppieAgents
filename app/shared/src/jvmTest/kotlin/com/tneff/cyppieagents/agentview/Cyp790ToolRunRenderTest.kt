package com.tneff.cyppieagents.agentview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test

/**
 * CYP-790 — the tool-run fold wired through the REAL [AgentWindow] transcript. Proves the op-po gate (§14), the
 * collapsed-by-default clean run with its visible count (Zahn 1), the operator toggle, and the fail-loud error
 * run (Zahn 2). The pure predicate is [Cyp790ToolRunFoldingTest]; this pins that it actually renders.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp790ToolRunRenderTest {

    private fun session(vararg events: AgentEvent) = object : AgentSession {
        override val events: Flow<AgentEvent> = flowOf(*events)
        override fun sendMessage(text: String) {}
    }

    /** Five clean ToolCalls CLOSED by a trailing answer (so the run is not the buffer tail, §3 Zahn 3) → one
     *  5-step run that folds (≥ threshold, no error, followed). */
    private fun cleanRun(): Array<AgentEvent> = buildList<AgentEvent> {
        repeat(5) { add(AgentEvent.ToolCall("tc$it", "hub", "send-$it", ToolStatus.OK, tsMs = it.toLong())) }
        add(AgentEvent.AssistantText("ans", "Antwort", complete = true, tsMs = 5))
    }.toTypedArray()

    @Test
    fun poWindow_cleanRun_collapsedByDefault_showsCount_thenExpands() = runComposeUiTest {
        val s = session(*cleanRun())
        setContent { MaterialTheme { val vm = remember { AgentViewModel(s, "po") }; AgentWindow(agentId = "po", viewModel = vm) } }

        // The fold header renders, collapsed by default: the count is shown (Zahn 1) and the child rows are hidden.
        waitUntil { onAllNodesWithTag(AgentViewTags.toolRun("po", 0), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // Zahn 1 — the step count is visible on the collapsed header. Assert the digit (locale-robust: "5 Schritte" /
        // "5 steps"); it is unique in the collapsed header — the 5 child rows (which bear "send-N") are hidden.
        onNodeWithText("5", substring = true, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag(AgentViewTags.event("po", 0, EventKind.TOOL_CALL), useUnmergedTree = true).assertDoesNotExist()

        // Operator expands → the original child rows appear (nothing was destroyed).
        onNodeWithTag(AgentViewTags.toolRunToggle("po", 0), useUnmergedTree = true).performClick()
        waitUntil {
            onAllNodesWithTag(AgentViewTags.event("po", 0, EventKind.TOOL_CALL), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun nonPoWindow_sameRun_notFolded() {
        // The op-po Boolean gate (the PO's added tooth): a NON-po window with the SAME run has NO fold header —
        // its ToolCalls render as individual rows. Mutation: call-site `foldToolRuns = true` (global) → a toolRun
        // header appears on backend → this REDs.
        runComposeUiTest {
            val s = session(*cleanRun())
            setContent {
                MaterialTheme { val vm = remember { AgentViewModel(s, "backend") }; AgentWindow(agentId = "backend", viewModel = vm) }
            }
            waitUntil {
                onAllNodesWithTag(AgentViewTags.event("backend", 0, EventKind.TOOL_CALL), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag(AgentViewTags.toolRun("backend", 0), useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test
    fun poWindow_errorRun_comesOpen_withErrorMarker() = runComposeUiTest {
        // Zahn 2: a run containing an ERROR groups but comes OPEN (children visible) with the fail-loud marker.
        val events = arrayOf<AgentEvent>(
            AgentEvent.ToolCall("tc0", "hub", "s0", ToolStatus.OK, tsMs = 0),
            AgentEvent.ToolCall("tc1", "hub", "s1", ToolStatus.ERROR, tsMs = 1),
            AgentEvent.ToolCall("tc2", "hub", "s2", ToolStatus.OK, tsMs = 2),
            AgentEvent.ToolCall("tc3", "hub", "s3", ToolStatus.OK, tsMs = 3),
            AgentEvent.AssistantText("ans", "Antwort", complete = true, tsMs = 4), // closes the run (not buffer tail)
        )
        val s = session(*events)
        setContent { MaterialTheme { val vm = remember { AgentViewModel(s, "po") }; AgentWindow(agentId = "po", viewModel = vm) } }

        waitUntil { onAllNodesWithTag(AgentViewTags.toolRun("po", 0), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // The header carries the fail-loud error marker...
        onNodeWithTag(AgentViewTags.toolRunErrors("po", 0), useUnmergedTree = true).assertIsDisplayed()
        // ...and the run is OPEN by default — the child rows (incl. the error) are visible, not hidden behind a bar.
        onNodeWithTag(AgentViewTags.event("po", 1, EventKind.TOOL_CALL), useUnmergedTree = true).assertIsDisplayed()
        // UIUX §-QA fix ②: the OPEN error header ANNOUNCES the error count too (SR == sighted). The toggle's
        // contentDescription carries the "N failed" clause even while expanded (the 1 error). Mutation (with-errors
        // cd only in the collapsed branch) → the expanded cd is the plain "N steps" (no "1") → this REDs.
        onNodeWithTag(AgentViewTags.toolRunToggle("po", 0), useUnmergedTree = true).assert(
            SemanticsMatcher("contentDescription announces the error count") { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.contains("1") } == true
            },
        )
    }
}
