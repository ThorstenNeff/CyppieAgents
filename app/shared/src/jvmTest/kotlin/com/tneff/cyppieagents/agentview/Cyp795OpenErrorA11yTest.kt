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
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test

/**
 * CYP-795 (a11y fast-follow to CYP-790 §-QA fix ②) — an OPEN error tool-run header must announce the error COUNT
 * WITHOUT the "collapsed"/"eingeklappt" wording. Error runs come open (Zahn 2), so reusing the
 * `…collapsed_with_errors` string made a screenreader hear "collapsed" on an expanded run. This pins the dedicated
 * open-error a11y string on the real [AgentWindow] header.
 *
 * Reddening mutation: point the `!collapsed && hasError` cd branch back at `a11y_transcript_tool_run_collapsed_with_errors`
 * → the open header cd contains "collapsed"/"eingeklappt" → RED. Locale-robust: asserts NEITHER word (DE + EN).
 */
@OptIn(ExperimentalTestApi::class)
class Cyp795OpenErrorA11yTest {

    private fun session(vararg events: AgentEvent) = object : AgentSession {
        override val events: Flow<AgentEvent> = flowOf(*events)
        override fun sendMessage(text: String) {}
    }

    @Test
    fun openErrorHeader_announcesCount_withoutCollapsedWording() = runComposeUiTest {
        // A 4-step run with one ERROR, closed by a trailing answer → grouped, comes OPEN (Zahn 2).
        val s = session(
            AgentEvent.ToolCall("tc0", "hub", "s0", ToolStatus.OK, tsMs = 0),
            AgentEvent.ToolCall("tc1", "hub", "s1", ToolStatus.ERROR, tsMs = 1),
            AgentEvent.ToolCall("tc2", "hub", "s2", ToolStatus.OK, tsMs = 2),
            AgentEvent.ToolCall("tc3", "hub", "s3", ToolStatus.OK, tsMs = 3),
            AgentEvent.AssistantText("ans", "Antwort", complete = true, tsMs = 4),
        )
        setContent { MaterialTheme { val vm = remember { AgentViewModel(s, "po") }; AgentWindow(agentId = "po", viewModel = vm) } }

        waitUntil {
            onAllNodesWithTag(AgentViewTags.toolRunToggle("po", 0), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(AgentViewTags.toolRunToggle("po", 0), useUnmergedTree = true).assert(
            SemanticsMatcher("open error cd carries the count but no collapsed wording") { node ->
                val cd = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
                    ?: return@SemanticsMatcher false
                cd.contains("1") && !cd.contains("eingeklappt") && !cd.contains("collapsed")
            },
        )
    }
}
