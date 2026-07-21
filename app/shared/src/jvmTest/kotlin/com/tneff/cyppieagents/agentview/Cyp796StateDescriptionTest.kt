package com.tneff.cyppieagents.agentview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test

/**
 * CYP-796 — the fold header's `stateDescription` carries the STATE (collapsed/expanded), NOT the ACTION (which
 * belongs on `onClickLabel`). Pre-existing since CYP-790. Locale-robust: asserts the bare state word
 * ("eingeklappt"/"ausgeklappt" | "collapsed"/"expanded"), which is disjoint from the action phrasing
 * ("Schritte anzeigen"/"einklappen" | "Show/Collapse steps").
 *
 * Reddening mutation: set `stateDescription = toggleLabel` (the action) → the collapsed header's stateDescription
 * is "Schritte anzeigen"/"Show steps" → not a state word → RED.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp796StateDescriptionTest {

    private fun session(vararg events: AgentEvent) = object : AgentSession {
        override val events: Flow<AgentEvent> = flowOf(*events)
        override fun sendMessage(text: String) {}
    }

    /** Five clean ToolCalls closed by an answer → one 5-step run that folds (collapsed by default). */
    private fun cleanRun(): Array<AgentEvent> = buildList<AgentEvent> {
        repeat(5) { add(AgentEvent.ToolCall("tc$it", "hub", "s$it", ToolStatus.OK, tsMs = it.toLong())) }
        add(AgentEvent.AssistantText("ans", "Antwort", complete = true, tsMs = 5))
    }.toTypedArray()

    @Test
    fun foldHeader_stateDescription_isStateWord_notAction() = runComposeUiTest {
        val s = session(*cleanRun())
        setContent { MaterialTheme { val vm = remember { AgentViewModel(s, "po") }; AgentWindow(agentId = "po", viewModel = vm) } }
        val toggle = AgentViewTags.toolRunToggle("po", 0)

        waitUntil { onAllNodesWithTag(toggle, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // Collapsed by default → stateDescription is the collapsed STATE word (never the "anzeigen/Show" action).
        onNodeWithTag(toggle, useUnmergedTree = true).assert(
            SemanticsMatcher("collapsed stateDescription is a state word") { node ->
                node.config.getOrNull(SemanticsProperties.StateDescription).let { it == "eingeklappt" || it == "collapsed" }
            },
        )

        // Toggle open → stateDescription flips to the expanded STATE word (proves it tracks the state, not the action).
        onNodeWithTag(toggle, useUnmergedTree = true).performClick()
        waitUntil {
            onAllNodesWithTag(toggle, useUnmergedTree = true).fetchSemanticsNodes()
                .firstOrNull()?.config?.getOrNull(SemanticsProperties.StateDescription)
                .let { it == "ausgeklappt" || it == "expanded" }
        }
    }
}
