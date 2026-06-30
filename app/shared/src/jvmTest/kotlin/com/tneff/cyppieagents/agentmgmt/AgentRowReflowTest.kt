package com.tneff.cyppieagents.agentmgmt

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.agentview.AgentViewTags
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.Role
import kotlin.test.Test

/**
 * CYP-156 §3.1: below `PANE_COLLAPSE_WIDTH` the 5-column [AgentRow] reflows to 2 lines (identity, then
 * actions) so the edit/remove buttons stay ON-SCREEN instead of being squeezed off the right edge. The
 * only-PO-unremovable guardrail stays visible+disabled before any action. Tags unchanged.
 */
@OptIn(ExperimentalTestApi::class)
class AgentRowReflowTest {

    private fun agent(id: String, role: Role) = Agent(id, id.uppercase(), role, id, AgentRunState.RUNNING)

    @Test
    fun narrowWidth_agentRowReflows_actionsOnScreen_guardrailHolds() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember {
                    AgentManagementViewModel(
                        StubAgentManagementRepository(listOf(agent("po", Role.PO), agent("fe", Role.WORKER))),
                        editable = true,
                    )
                }
                Box(Modifier.width(360.dp).height(700.dp)) { AgentManagementPanel(vm) }
            }
        }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AgentMgmtTags.item("fe")).fetchSemanticsNodes().isNotEmpty() }

        // 2-line reflow → identity + both action buttons are on-screen (line 2), not off the right edge.
        onNodeWithTag(AgentViewTags.status("fe")).assertIsDisplayed()
        onNodeWithTag(AgentMgmtTags.itemEdit("fe")).assertIsDisplayed()
        onNodeWithTag(AgentMgmtTags.itemRemove("fe")).assertIsDisplayed()

        // Guardrail survives the reflow: the only PO's remove stays visible AND disabled.
        onNodeWithTag(AgentMgmtTags.itemRemove("po")).assertIsDisplayed()
        onNodeWithTag(AgentMgmtTags.itemRemove("po")).assertIsNotEnabled()
    }
}
