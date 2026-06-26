package com.tneff.cyppieagents

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.agentview.AgentViewTags
import com.tneff.cyppieagents.window.WindowTestTags
import kotlin.test.Test

/**
 * CYP-15: proves the shell actually marries the window manager (CYP-10) with the renderer (CYP-6) —
 * the window host renders, and every agent gets its own AgentWindow content (a per-agent stream).
 */
@OptIn(ExperimentalTestApi::class)
class AgentShellRenderTest {

    @Test
    fun shell_rendersWindowHostWithPerAgentStreams() = runComposeUiTest {
        setContent { MaterialTheme { AgentShell() } }

        onNodeWithTag(WindowTestTags.HOST).assertExists()
        // One AgentWindow per agent, addressed by its v0.5 stream tag.
        onNodeWithTag(AgentViewTags.stream("po")).assertExists()
        onNodeWithTag(AgentViewTags.stream("frontend")).assertExists()
        onNodeWithTag(AgentViewTags.stream("backend")).assertExists()
    }
}
