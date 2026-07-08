package com.tneff.cyppieagents.agentmgmt

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test

/**
 * CYP-314 (follow-up CYP-312): the "agent created" confirmation lands as a panel-level INFO hint naming the
 * agent — and, crucially, **only after a verified repository success** (the CYP-312 lesson: never claim
 * success before the server roundtrip confirms it). The three axes the PO asked for, all mutation-provable:
 *
 *  - **Positive:** a real add (VM → StubRepository roundtrip) surfaces the [AgentMgmtTags.ADD_SUCCESS] hint,
 *    interpolating the agent's display name, with `liveRegion=Polite` (UIUX §8 — the hint appears async after
 *    the dialog closes, focus gone, so it must be announced).
 *  - **M1 (false-optimistic):** the hint is absent before any add AND stays absent when the add FAILS
 *    (denyWrites → the repo throws). A mutation that sets `addSuccessName` optimistically (before the repo
 *    call, or in the failure branch) turns these RED.
 *  - **M2 (wrong tone):** the hint carries the INFO tone glyph `i` (never the ERROR glyph `✕`). Flipping the
 *    tone to ERROR changes the glyph → the scoped glyph assertion turns RED.
 *
 * Uses an [Dispatchers.Unconfined] VM scope so the init-load and the confirmAdd roundtrip settle synchronously.
 */
@OptIn(ExperimentalTestApi::class)
class AgentAddSuccessMessageTest {

    private fun vm(repo: AgentManagementRepository) =
        AgentManagementViewModel(repo, editable = true, scope = CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun realAdd_surfacesInfoHint_namingAgent_announcedPolitely() = runComposeUiTest {
        val model = vm(StubAgentManagementRepository(emptyList()))
        setContent { MaterialTheme { AgentManagementPanel(model) } }

        // No confirmation before an add has happened (M1 part a).
        onNodeWithTag(AgentMgmtTags.ADD_SUCCESS).assertDoesNotExist()

        // A real add: open → fill required id/name → confirm. The StubRepository enforces the same invariants
        // the server keeps, so this is the real success path (not a hard-coded flag).
        model.openAdd()
        model.setAddId("qa")
        model.setAddName("QA-Bot")
        model.confirmAdd()
        waitForIdle()

        // The panel-level confirmation appears, naming the created agent — asserted INSIDE the hint (scoped to
        // its own child text: the name also appears in the new agent's list row after the re-fetch).
        onNodeWithTag(AgentMgmtTags.ADD_SUCCESS).assertExists()
        onNodeWithTag(AgentMgmtTags.ADD_SUCCESS, useUnmergedTree = true)
            .onChildren().filterToOne(hasText("QA-Bot", substring = true)).assertExists()

        // M2 — tone honesty: the INFO glyph `i` renders inside the hint; the ERROR glyph `✕` never does. Scoped
        // to the hint's own children so it can't be confused with an `i`/`✕` elsewhere on the panel.
        onNodeWithTag(AgentMgmtTags.ADD_SUCCESS, useUnmergedTree = true)
            .onChildren().filterToOne(hasText("i")).assertExists()

        // UIUX §8 (mandatory): the confirmation is a Polite live region — dropping it turns this RED.
        onNodeWithTag(AgentMgmtTags.ADD_SUCCESS)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun failedAdd_showsNoSuccessHint_onlyError() = runComposeUiTest {
        // The operator gate (server 403) — the repo rejects the write. A false-optimistic implementation would
        // still flash the success hint; the honest one shows only the error (M1 part b — the CYP-312 lesson).
        val model = vm(StubAgentManagementRepository(emptyList(), denyWrites = "operator_required"))
        setContent { MaterialTheme { AgentManagementPanel(model) } }

        model.openAdd()
        model.setAddId("qa")
        model.setAddName("QA-Bot")
        model.confirmAdd()
        waitForIdle()

        onNodeWithTag(AgentMgmtTags.ADD_SUCCESS).assertDoesNotExist()
        onNodeWithTag(AgentMgmtTags.ADD_ERROR).assertExists()
    }

    @Test
    fun reopeningAdd_clearsPriorSuccessHint() = runComposeUiTest {
        // Clear semantics mirror editEffectHint: a fresh add cycle starts clean — the prior confirmation is gone.
        val model = vm(StubAgentManagementRepository(emptyList()))
        setContent { MaterialTheme { AgentManagementPanel(model) } }

        model.openAdd(); model.setAddId("qa"); model.setAddName("QA-Bot"); model.confirmAdd()
        waitForIdle()
        onNodeWithTag(AgentMgmtTags.ADD_SUCCESS).assertExists()

        model.openAdd()
        waitForIdle()
        onNodeWithTag(AgentMgmtTags.ADD_SUCCESS).assertDoesNotExist()
    }
}
