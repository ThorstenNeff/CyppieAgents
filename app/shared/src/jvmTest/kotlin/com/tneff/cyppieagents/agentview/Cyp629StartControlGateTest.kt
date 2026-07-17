package com.tneff.cyppieagents.agentview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_agent_ctl_unconfigured
import kmpcyppieagents.app.shared.generated.resources.agent_ctl_unconfigured
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test

/**
 * CYP-629 §6.3c — the honest GATED start-block when the hub is unconfigured. This is an **outcome** tooth per the PO:
 * the Start control must be truly unreachable AND the reason must be AT the control — not merely "a reason was
 * computed". Mutation = sever the wiring (`&& !hubUnconfigured` dropped: reason still shown, control still live) ⇒ the
 * `assertIsNotEnabled` reddens.
 *
 * **Barrier isolation (the terminal-revoke two-barrier lesson):** Start already has TWO other barriers — the operator
 * gate (`canControl`) and the running-state gate (`state != RUNNING`). If both of those ALSO blocked in the test, a
 * dropped unconfigured-gate would stay green (another barrier masks it). So the gated case holds both other barriers
 * OPEN (operator = true, state = STOPPED), making `hubUnconfigured` the SOLE reason; the paired configured case (same
 * state, gate off) proves the Start flips to enabled — so the tooth pins THAT this barrier fires, not just that Start
 * is unreachable.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp629StartControlGateTest {

    private fun emptySession() = object : AgentSession {
        override val events: Flow<AgentEvent> = emptyFlow()
        override fun sendMessage(text: String) {}
    }

    private fun stopped() = StubAgentLifecycle(mapOf("backend" to AgentLifecycleState.STOPPED))

    @Test
    fun unconfigured_isSoleBarrier_startBlocked_reasonAtControl() = runComposeUiTest {
        lateinit var reason: String
        lateinit var a11yReason: String
        setContent {
            MaterialTheme {
                reason = stringResource(Res.string.agent_ctl_unconfigured)
                a11yReason = stringResource(Res.string.a11y_agent_ctl_unconfigured)
                val src = remember { stopped() }
                // Operator + STOPPED → the operator gate and the running gate are BOTH open. The only thing that can
                // block Start is the unconfigured gate.
                val vm = remember {
                    AgentViewModel(emptySession(), agentId = "backend", lifecycle = src, lifecycleSource = src, canControl = true)
                }
                AgentWindow(agentId = "backend", viewModel = vm, hubUnconfigured = true)
            }
        }
        // Outcome: Start is truly unreachable.
        onNodeWithTag(AgentViewTags.startBtn("backend")).assertIsNotEnabled()
        // Reason AT the control: the visible GATED hint carries the ratified `agent_ctl_unconfigured` copy...
        onNodeWithTag(AgentViewTags.startBtnGateHint("backend")).assertExists()
        onNodeWithText(reason).assertExists()
        // ...and the a11y reason rides ON the Start button as its stateDescription (a11y-spec §1 — the WHY, spoken).
        onNodeWithTag(AgentViewTags.startBtn("backend"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, a11yReason))
    }

    @Test
    fun configured_sameState_startEnabled_noGateHint() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val src = remember { stopped() }
                val vm = remember {
                    AgentViewModel(emptySession(), agentId = "backend", lifecycle = src, lifecycleSource = src, canControl = true)
                }
                // The ONLY change from the gated case is hubUnconfigured=false → this isolates the barrier: Start flips
                // to enabled and the reason vanishes. (Also the byte-identical default path — no live wiring yet.)
                AgentWindow(agentId = "backend", viewModel = vm, hubUnconfigured = false)
            }
        }
        onNodeWithTag(AgentViewTags.startBtn("backend")).assertIsEnabled()
        onNodeWithTag(AgentViewTags.startBtnGateHint("backend")).assertDoesNotExist()
    }
}
