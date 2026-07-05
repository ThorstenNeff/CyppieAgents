package com.tneff.cyppieagents.agentsettings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.agentmgmt.AgentManagementRepository
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentDetail
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.WorktreeFate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-211 — the settings panel's disclosure invariants (§7/§10), teethed: id read-only (identity ≠ name),
 * live-vs-deferred (effect hint ONLY on persona change), invalid-hex ERROR (no unreadable save), operator gate.
 */
@OptIn(ExperimentalTestApi::class)
class AgentSettingsPanelTest {

    private class FakeRepo(private val detail: AgentDetail) : AgentManagementRepository {
        override suspend fun list(): List<Agent> = emptyList()
        override suspend fun detail(id: String): AgentDetail = detail
        override suspend fun add(spec: NewAgentSpec): Agent = error("unused")
        override suspend fun edit(id: String, edit: AgentEdit): Agent = Agent(id, detail.name, detail.role, detail.worktree)
        override suspend fun remove(id: String, worktree: WorktreeFate) {}
    }

    /** CYP-237: `edit` FAILS (server 500 / network) → save() takes the onFailure path → state.error=true, saved=false. */
    private class FailingEditRepo(private val detail: AgentDetail) : AgentManagementRepository {
        override suspend fun list(): List<Agent> = emptyList()
        override suspend fun detail(id: String): AgentDetail = detail
        override suspend fun add(spec: NewAgentSpec): Agent = error("unused")
        override suspend fun edit(id: String, edit: AgentEdit): Agent = throw RuntimeException("save failed")
        override suspend fun remove(id: String, worktree: WorktreeFate) {}
    }

    private val detail = AgentDetail("backend", "Backend", Role.WORKER, "backend", "bash", persona = "persona v1", color = null)
    private fun vm(editable: Boolean) = AgentSettingsViewModel(
        "backend", FakeRepo(detail), editable, initialName = "Backend", initialColorHex = null,
        scope = CoroutineScope(Dispatchers.Unconfined), // load() resolves synchronously before assertions
    )
    private fun vmFailingSave() = AgentSettingsViewModel(
        "backend", FailingEditRepo(detail), editable = true, initialName = "Backend", initialColorHex = null,
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun operator_nameEditable_idReadonly_present_and_effectHintOnlyPostPersonaSave() = runComposeUiTest {
        val v = vm(editable = true)
        setContent { MaterialTheme { AgentSettingsPanel(v, onDismiss = {}) } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AgentSettingsTags.PANEL).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithTag(AgentSettingsTags.NAME_INPUT).assertIsEnabled()
        onNodeWithTag(AgentSettingsTags.ID_READONLY).assertExists()      // stable identity shown, not editable
        // Rename/recolour are immediate → NO restart hint, ever.
        v.setName("Renamed"); v.setColorHex("#3B82F6"); waitForIdle()
        onNodeWithTag(AgentSettingsTags.EFFECT_HINT).assertDoesNotExist()
        // ⭐ UX-QA: editing the persona pre-save must NOT show the "Gespeichert…" hint (nothing saved yet).
        v.setPersona("persona v2"); waitForIdle()
        onNodeWithTag(AgentSettingsTags.EFFECT_HINT).assertDoesNotExist()
        // Only AFTER the save (saved ≠ active → restart) does the hint appear.
        v.save(); waitForIdle()
        onNodeWithTag(AgentSettingsTags.EFFECT_HINT).assertExists()
    }

    /**
     * CYP-237 defect-1 (close-on-save): clicking SAVE on a successful edit fires [onSaved] exactly once (the host
     * turns that into "close the overlay + refresh the live list"). Before the fix the panel called `save()` and
     * nothing observed `state.saved` → the dialog stayed open. Mutation: drop the `LaunchedEffect(state.saved)` →
     * onSaved never fires → this waitUntil times out → RED.
     */
    @Test
    fun successfulSave_firesOnSaved_forCloseAndRefresh() = runComposeUiTest {
        val v = vm(editable = true)
        var onSavedCount = 0
        setContent { MaterialTheme { AgentSettingsPanel(v, onDismiss = {}, onSaved = { onSavedCount++ }) } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AgentSettingsTags.PANEL).fetchSemanticsNodes().isNotEmpty() }
        v.setName("Renamed"); waitForIdle()
        onNodeWithTag(AgentSettingsTags.SAVE).performClick()
        // save() success flips state.saved → the close-on-save LaunchedEffect signals the host.
        waitUntil(timeoutMillis = 5_000L) { onSavedCount >= 1 }
        assertTrue(onSavedCount >= 1, "a successful save must signal onSaved (host closes + refreshes)")
    }

    /**
     * CYP-237 fail-save invariant (Test-found teeth gap): a save that FAILS (state.error=true, state.saved stays
     * false) must NOT fire onSaved → the host keeps the overlay open (no silent data-loss close). Correct-by-
     * construction today (the LaunchedEffect is keyed on `saved`, not `error`), but unguarded. Mutation: fire the
     * effect unconditionally / re-key it on `error` → onSaved fires on failure → this asserts RED.
     */
    @Test
    fun failedSave_doesNotFireOnSaved_dialogStaysOpen() = runComposeUiTest {
        val v = vmFailingSave()
        var onSavedCount = 0
        setContent { MaterialTheme { AgentSettingsPanel(v, onDismiss = {}, onSaved = { onSavedCount++ }) } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AgentSettingsTags.PANEL).fetchSemanticsNodes().isNotEmpty() }
        v.setName("Renamed"); waitForIdle()
        onNodeWithTag(AgentSettingsTags.SAVE).performClick()
        // Let the FAILING save settle — the VM reports the error; saved must remain false.
        waitUntil(timeoutMillis = 5_000L) { v.state.value.error }
        waitForIdle()
        assertEquals(false, v.state.value.saved, "a failed save must leave state.saved false")
        assertEquals(0, onSavedCount, "a FAILED save must NOT fire onSaved (dialog stays open — no data-loss close)")
    }

    @Test
    fun invalidHex_showsError_andDisablesSave() = runComposeUiTest {
        val v = vm(editable = true)
        setContent { MaterialTheme { AgentSettingsPanel(v, onDismiss = {}) } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AgentSettingsTags.PANEL).fetchSemanticsNodes().isNotEmpty() }
        v.setColorHex("not-a-hex"); waitForIdle()
        onNodeWithTag(AgentSettingsTags.CUSTOM_HEX_ERROR).assertExists()
        onNodeWithTag(AgentSettingsTags.SAVE).assertIsNotEnabled() // no unreadable colour can be saved
    }

    @Test
    fun nonOperator_gateHint_and_nameDisabled_failClosed() = runComposeUiTest {
        val v = vm(editable = false)
        setContent { MaterialTheme { AgentSettingsPanel(v, onDismiss = {}) } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AgentSettingsTags.PANEL).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AgentSettingsTags.GATE_HINT).assertExists()
        onNodeWithTag(AgentSettingsTags.NAME_INPUT).assertIsNotEnabled()
        onNodeWithTag(AgentSettingsTags.SAVE).assertIsNotEnabled()
    }
}
