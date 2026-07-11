package com.tneff.cyppieagents.agentview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_agent_status
import kmpcyppieagents.app.shared.generated.resources.agent_status_error
import kmpcyppieagents.app.shared.generated.resources.agent_status_running
import kmpcyppieagents.app.shared.generated.resources.agent_status_stopped
import kmpcyppieagents.app.shared.generated.resources.agent_status_unknown
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertNotEquals

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

    /**
     * CYP-396 T-B — a rendered `UNKNOWN` shows its OWN label, never STOPPED/RUNNING (honesty on the render axis).
     * Reddening mutation: point the `UNKNOWN` branch of the label `when` at `agent_status_stopped` ⇒ the unknown
     * label is absent (the stopped label shows) ⇒ red.
     */
    @Test
    fun unknownRendersItsOwnLabel_notStoppedOrRunning() = runComposeUiTest {
        lateinit var unknownLabel: String
        lateinit var stoppedLabel: String
        lateinit var runningLabel: String
        setContent {
            MaterialTheme {
                unknownLabel = stringResource(Res.string.agent_status_unknown)
                stoppedLabel = stringResource(Res.string.agent_status_stopped)
                runningLabel = stringResource(Res.string.agent_status_running)
                val src = remember { StubAgentLifecycle(mapOf("backend" to AgentLifecycleState.UNKNOWN)) }
                val vm = remember {
                    AgentViewModel(emptySession(), agentId = "backend", lifecycle = src, lifecycleSource = src, canControl = false)
                }
                AgentWindow(agentId = "backend", viewModel = vm)
            }
        }
        onNodeWithTag(AgentViewTags.status("backend")).assertExists() // status is ungated
        onNodeWithText(unknownLabel).assertExists()
        onNodeWithText(stoppedLabel).assertDoesNotExist()
        onNodeWithText(runningLabel).assertDoesNotExist()
    }

    /**
     * CYP-396 T-C — the a11y voice separates `UNKNOWN` from `ERROR` (the status contentDescription is
     * `a11y_agent_status(label)`, and the two state labels differ). Regression tooth: if both states ever
     * resolved to the same label, the descriptions would collapse ⇒ red. (Green today; kept so the ring fix
     * cannot silently flatten the distinction.)
     */
    @Test
    fun a11yVoiceDistinguishesUnknownFromError() = runComposeUiTest {
        lateinit var unknownDesc: String
        lateinit var errorDesc: String
        setContent {
            MaterialTheme {
                unknownDesc = stringResource(Res.string.a11y_agent_status, stringResource(Res.string.agent_status_unknown))
                errorDesc = stringResource(Res.string.a11y_agent_status, stringResource(Res.string.agent_status_error))
            }
        }
        assertNotEquals(unknownDesc, errorDesc)
    }
}
