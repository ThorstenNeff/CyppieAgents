package com.tneff.cyppieagents.connector

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tneff.cyppieagents.agentmgmt.AgentManagementViewModel
import com.tneff.cyppieagents.agentmgmt.StubAgentManagementRepository
import com.tneff.cyppieagents.model.ConnectorKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-260 (test-only fast-follow to CYP-258): the teeth gap on the connector-add data-integrity fix. The
 * add-connector picker VM's factory captures a method-ref `agentMgmtVm::setAddConnectorKind`; agentMgmtVm
 * re-instantiates per project (CYP-246). Keyed by a CONSTANT (`connectorSelection-add`) it is retained across a
 * project switch, so its captured ref still points at the PREVIOUS project's agentMgmtVm → a connector chosen in
 * the new project lands on the OLD project's add form, and the agent created in the new project silently gets the
 * default connector. CYP-258 keyed it on `activeProjectId`; this pins that fix with a differential tooth.
 *
 * Mirrors the crossproject tooth: a single parameterized harness ([keyWithProject]) exercises AgentShell's exact
 * add-picker key expression (buggy constant vs project-scoped fix) with agentMgmtVm re-keyed per project. The KEY
 * is the built-in mutation — constant → leaks to the old project, project-scoped → lands on the current one.
 */
@OptIn(ExperimentalTestApi::class)
class ConnectorAddSwitchScopeTest {

    /** Per-project instances captured out of composition so the test can assert which add form received the kind. */
    private class Captured {
        val mgmt = mutableMapOf<String, AgentManagementViewModel>()
        val add = mutableMapOf<String, ConnectorSelectionViewModel>()
    }

    @Composable
    private fun Harness(active: State<String>, keyWithProject: Boolean, cap: Captured) {
        val a = active.value
        // agentMgmtVm re-keyed per project, exactly as CYP-246 (fresh instance on switch).
        val agentMgmtVm = viewModel(key = "agentMgmt-$a") {
            AgentManagementViewModel(StubAgentManagementRepository(), editable = true)
        }
        // The add-connector picker VM — AgentShell's key expression. Its factory captures agentMgmtVm's method-ref.
        val addKey = if (keyWithProject) "connectorSelection-add-$a" else "connectorSelection-add"
        val addVm = viewModel(key = addKey) {
            ConnectorSelectionViewModel(
                StubConnectorSelectionRepository(), agentId = null, editable = true,
                initialKind = ConnectorKind.STREAM_JSON, onKindChosen = agentMgmtVm::setAddConnectorKind,
            )
        }
        SideEffect {
            cap.mgmt[a] = agentMgmtVm
            cap.add[a] = addVm
        }
    }

    /** Settle the picker on B (MCP): select → ack-gated dialog → confirm fires onKindChosen(MCP) synchronously. */
    private fun chooseMcp(vm: ConnectorSelectionViewModel) {
        vm.selectKind(ConnectorKind.MCP) // B opens the deliberate opt-in dialog (draft not yet B)
        vm.setRiskAcknowledged(true)
        vm.confirmOptIn()                // add context → onKindChosen(MCP), no endpoint call
    }

    @Test
    fun constantKey_connectorChoiceLeaksToPreviousProjectsAddForm() = runComposeUiTest {
        val active = mutableStateOf("alpha")
        val cap = Captured()
        setContent { Harness(active, keyWithProject = false, cap) }
        waitForIdle()
        active.value = "beta"
        waitForIdle()

        // Operator picks B in beta through the (constant-keyed, retained) add picker — its captured method-ref
        // still points at ALPHA's agentMgmtVm → the choice lands on alpha's add form, not beta's.
        chooseMcp(cap.add.getValue("beta"))
        assertEquals(ConnectorKind.MCP, cap.mgmt.getValue("alpha").state.value.addForm.connectorKind, "the bug: leaked to alpha")
        assertEquals(ConnectorKind.STREAM_JSON, cap.mgmt.getValue("beta").state.value.addForm.connectorKind, "beta's form never got it")
    }

    @Test
    fun projectScopedKey_connectorChoiceLandsOnCurrentProjectsAddForm() = runComposeUiTest {
        val active = mutableStateOf("alpha")
        val cap = Captured()
        setContent { Harness(active, keyWithProject = true, cap) }
        waitForIdle()
        active.value = "beta"
        waitForIdle()

        // Fix: project-scoped key → a fresh add picker bound to BETA's agentMgmtVm → the choice lands on beta's form.
        chooseMcp(cap.add.getValue("beta"))
        assertEquals(ConnectorKind.MCP, cap.mgmt.getValue("beta").state.value.addForm.connectorKind, "the fix: lands on beta")
        assertEquals(ConnectorKind.STREAM_JSON, cap.mgmt.getValue("alpha").state.value.addForm.connectorKind, "alpha untouched")
    }
}
