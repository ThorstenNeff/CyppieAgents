package com.tneff.cyppieagents.agentview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test

/**
 * CYP-73 reviewer gate for the client header: the Start/Stop/Restart controls are **operator-gated /
 * fail-closed** (disabled without an operator token) while the lifecycle **status display is NOT
 * gated**. The contrast is pinned at a single lifecycle state (STOPPED) so the only variable is
 * `canControl` — the operator gate itself.
 */
@OptIn(ExperimentalTestApi::class)
class AgentLifecycleHeaderTest {

    private fun emptySession() = object : AgentSession {
        override val events: Flow<AgentEvent> = emptyFlow()
        override fun sendMessage(text: String) {}
    }

    private fun stopped() = StubAgentLifecycle(mapOf("backend" to AgentLifecycleState.STOPPED))

    @Test
    fun operator_canControl_startEnabled_statusShown() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val src = remember { stopped() }
                val vm = remember {
                    AgentViewModel(emptySession(), agentId = "backend", lifecycle = src, lifecycleSource = src, canControl = true)
                }
                AgentWindow(agentId = "backend", viewModel = vm)
            }
        }
        // STOPPED → Start is the available action (enabled UNKNOWN→STOPPED, no flake); Stop is not.
        onNodeWithTag(AgentViewTags.startBtn("backend")).assertIsEnabled()
        onNodeWithTag(AgentViewTags.stopBtn("backend")).assertIsNotEnabled()
        // Status display is non-gated — present regardless of operator rights.
        onNodeWithTag(AgentViewTags.status("backend")).assertExists()
    }

    @Test
    fun nonOperator_failClosed_allControlsDisabled_statusStillShown() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val src = remember { stopped() }
                val vm = remember {
                    AgentViewModel(emptySession(), agentId = "backend", lifecycle = src, lifecycleSource = src, canControl = false)
                }
                AgentWindow(agentId = "backend", viewModel = vm)
            }
        }
        // Same STOPPED state, but no operator token → EVERY control disabled (fail-closed).
        onNodeWithTag(AgentViewTags.startBtn("backend")).assertIsNotEnabled()
        onNodeWithTag(AgentViewTags.stopBtn("backend")).assertIsNotEnabled()
        onNodeWithTag(AgentViewTags.restartBtn("backend")).assertIsNotEnabled()
        // ... yet the status is still shown (display non-gated).
        onNodeWithTag(AgentViewTags.status("backend")).assertExists()
    }
}
