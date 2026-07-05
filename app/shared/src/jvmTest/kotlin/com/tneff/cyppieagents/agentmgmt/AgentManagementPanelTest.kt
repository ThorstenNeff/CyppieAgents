package com.tneff.cyppieagents.agentmgmt

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.Role
import kotlin.test.Test

/**
 * CYP-86/87/88 render gate: the panel lists agents, shows the operator gate honestly (visible but
 * disabled without a token), and surfaces the guardrails **before** the action — the PO role option is
 * disabled when a PO exists, and the only PO's remove is disabled. testTags exactly per
 * `docs/design/agent-management-tags.md`.
 */
@OptIn(ExperimentalTestApi::class)
class AgentManagementPanelTest {

    private fun agent(id: String, role: Role) = Agent(id, id.uppercase(), role, id, AgentRunState.RUNNING)

    @Test
    fun operator_listsAgents_addEnabled_onlyPoUnremovable() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember {
                    AgentManagementViewModel(
                        StubAgentManagementRepository(listOf(agent("po", Role.PO), agent("fe", Role.WORKER))),
                        editable = true,
                    )
                }
                AgentManagementPanel(vm)
            }
        }
        onNodeWithTag(AgentMgmtTags.PANEL).assertExists()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AgentMgmtTags.item("po")).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(AgentMgmtTags.item("fe")).assertExists()
        onNodeWithTag(AgentMgmtTags.ADD_BUTTON).assertIsEnabled()
        onNodeWithTag(AgentMgmtTags.GATE_HINT).assertDoesNotExist()
        // The only PO is unremovable — guardrail visible (disabled) without opening anything.
        onNodeWithTag(AgentMgmtTags.itemRemove("po")).assertIsNotEnabled()
        onNodeWithTag(AgentMgmtTags.itemRemove("fe")).assertIsEnabled()
    }

    @Test
    fun noOperator_gateHint_mutationsDisabled() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember {
                    AgentManagementViewModel(
                        StubAgentManagementRepository(listOf(agent("po", Role.PO), agent("fe", Role.WORKER))),
                        editable = false,
                    )
                }
                AgentManagementPanel(vm)
            }
        }
        onNodeWithTag(AgentMgmtTags.GATE_HINT).assertExists()
        onNodeWithTag(AgentMgmtTags.ADD_BUTTON).assertIsNotEnabled()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AgentMgmtTags.item("fe")).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(AgentMgmtTags.itemEdit("fe")).assertIsNotEnabled()
        onNodeWithTag(AgentMgmtTags.itemRemove("fe")).assertIsNotEnabled()
    }

    @Test
    fun addDialog_poRoleDisabled_whenPoExists_spawnHintShown() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember {
                    AgentManagementViewModel(
                        StubAgentManagementRepository(listOf(agent("po", Role.PO))),
                        editable = true,
                    )
                }
                AgentManagementPanel(vm)
            }
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AgentMgmtTags.ADD_BUTTON).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(AgentMgmtTags.ADD_BUTTON).performClick()
        onNodeWithTag(AgentMgmtTags.ADD_DIALOG).assertExists()
        // Guardrail: PO disabled (a PO already exists); WORKER stays selectable.
        onNodeWithTag(AgentMgmtTags.roleOption(AgentMgmtTags.ADD_ROLE_PICKER, "PO")).assertIsNotEnabled()
        onNodeWithTag(AgentMgmtTags.roleOption(AgentMgmtTags.ADD_ROLE_PICKER, "WORKER")).assertIsEnabled()
        // Creating does not spawn — the disclosure hint is present.
        onNodeWithTag(AgentMgmtTags.ADD_SPAWN_HINT).assertExists()
    }

    @Test
    fun editDialog_onlyPoToWorker_showsLastPoReason_disablesSave() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember {
                    AgentManagementViewModel(
                        StubAgentManagementRepository(listOf(agent("po", Role.PO), agent("fe", Role.WORKER))),
                        editable = true,
                    )
                }
                AgentManagementPanel(vm)
            }
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AgentMgmtTags.itemEdit("po")).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(AgentMgmtTags.itemEdit("po")).performClick()
        onNodeWithTag(AgentMgmtTags.EDIT_DIALOG).assertExists()
        // The only PO → WORKER: the guardrail engages and confirm is disabled.
        onNodeWithTag(AgentMgmtTags.roleOption(AgentMgmtTags.EDIT_ROLE_PICKER, "WORKER")).performClick()
        onNodeWithTag(AgentMgmtTags.EDIT_SAVE).assertIsNotEnabled()
        // CYP-101 [Mittel 1]: the visible reason is the last-PO text, NOT the "PO taken elsewhere" text —
        // the two are now distinct. Locale-robust: only the last-PO wording mentions hub-and-spoke
        // ("Hub-and-Spoke" DE / "hub-and-spoke" EN → "ub-and-"); the po-taken wording never does.
        onNodeWithTag(AgentMgmtTags.EDIT_ERROR).assertExists()
        onNodeWithText("ub-and-", substring = true).assertExists()
    }

    // ── CYP-228: add-onboarding (inline field help + empty state) ─────────────────────────────────────

    @Test
    fun cyp228_emptyState_operator_showsOnboarding_addEnabled_noEmptyStateWhenAgentsExist() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember { AgentManagementViewModel(StubAgentManagementRepository(emptyList()), editable = true) }
                AgentManagementPanel(vm)
            }
        }
        // Empty list → the onboarding block appears; the CTA is the EXISTING add button (still enabled for an operator).
        onNodeWithTag(AgentMgmtTags.EMPTY).assertExists()
        onNodeWithTag(AgentMgmtTags.ADD_BUTTON).assertIsEnabled()
    }

    @Test
    fun cyp228_emptyState_nonOperator_showsOnboardingPlusGate_addDisabled_noDeadCta() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember { AgentManagementViewModel(StubAgentManagementRepository(emptyList()), editable = false) }
                AgentManagementPanel(vm)
            }
        }
        onNodeWithTag(AgentMgmtTags.EMPTY).assertExists()
        onNodeWithTag(AgentMgmtTags.GATE_HINT).assertExists()
        onNodeWithTag(AgentMgmtTags.ADD_BUTTON).assertIsNotEnabled() // no dead CTA
    }

    @Test
    fun cyp228_emptyState_absentWhenAgentsExist() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember { AgentManagementViewModel(StubAgentManagementRepository(listOf(agent("po", Role.PO))), editable = true) }
                AgentManagementPanel(vm)
            }
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AgentMgmtTags.item("po")).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(AgentMgmtTags.EMPTY).assertDoesNotExist()
    }

    @Test
    fun cyp228_addDialog_projectScopeNote_namesActiveProject_autoNote_andFieldHelp_present() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember { AgentManagementViewModel(StubAgentManagementRepository(emptyList()), editable = true) }
                AgentManagementPanel(vm, activeProjectName = "Cyppie-Prod")
            }
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AgentMgmtTags.ADD_BUTTON).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(AgentMgmtTags.ADD_BUTTON).performClick()
        onNodeWithTag(AgentMgmtTags.ADD_DIALOG).assertExists()
        // Project-scope note at the head — names the REAL active project (interpolated, locale-independent).
        onNodeWithTag(AgentMgmtTags.ADD_PROJECT_NOTE).assertExists()
        onNodeWithText("Cyppie-Prod", substring = true).assertExists()
        // Auto-assigned note present; existing "creating ≠ running" hint preserved.
        onNodeWithTag(AgentMgmtTags.ADD_AUTO_NOTE).assertExists()
        onNodeWithTag(AgentMgmtTags.ADD_SPAWN_HINT).assertExists()
        // Field help wired: the always-visible role-meaning line ("Worker = " in both locales). Placeholders are
        // focus-gated by Material3 (only shown on focus) → not assertable in an unfocused render; UIUX UX-QA covers them.
        onNodeWithText("Worker = ", substring = true).assertExists()
    }
}
