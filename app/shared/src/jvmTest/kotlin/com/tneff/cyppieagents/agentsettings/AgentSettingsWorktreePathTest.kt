package com.tneff.cyppieagents.agentsettings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import com.tneff.cyppieagents.agentmgmt.AgentManagementRepository
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentDetail
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.WorktreeFate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-315 — the read-only worktree-path row in [AgentSettingsPanel] (UIUX-spec §2/§8). The client reads the typed
 * [AgentDetail.worktreePath] straight off the DTO (no hand-parse) and the panel distinguishes four states via a
 * POSITIVE resolved signal, NOT bare `worktreePath == null`:
 *  - **Z1** resolved + path → the monospace path renders (== `detail.worktreePath`) with a copy button.
 *  - **Z2** resolved + null → the INFO "not local" hint (no path, no copy).
 *  - **Z4** the honesty core (§8-3, CYP-288 `failed ≠ remote`): a FAILED detail load also collapses to `null`, but is
 *    NOT resolved → the line renders NOTHING, never "not local".
 *  - copy → the real path is written to the clipboard + a transient INFO "copied" receipt (self-clears, §3).
 */
@OptIn(ExperimentalTestApi::class)
class AgentSettingsWorktreePathTest {

    /** A repo whose `detail()` returns a fixed [AgentDetail] (with a chosen `worktreePath`). */
    private class DetailRepo(private val detail: AgentDetail) : AgentManagementRepository {
        override suspend fun list(): List<Agent> = emptyList()
        override suspend fun detail(id: String): AgentDetail = detail
        override suspend fun add(spec: NewAgentSpec): Agent = error("unused")
        override suspend fun edit(id: String, edit: AgentEdit): Agent = Agent(id, detail.name, detail.role, detail.worktree)
        override suspend fun remove(id: String, worktree: WorktreeFate) {}
    }

    /** A repo whose `detail()` FAILS → the VM's `load()` collapses to defaults (`worktreePath=null`, `detailResolved=false`). */
    private class FailingDetailRepo : AgentManagementRepository {
        override suspend fun list(): List<Agent> = emptyList()
        override suspend fun detail(id: String): AgentDetail = throw RuntimeException("detail load failed")
        override suspend fun add(spec: NewAgentSpec): Agent = error("unused")
        override suspend fun edit(id: String, edit: AgentEdit): Agent = error("unused")
        override suspend fun remove(id: String, worktree: WorktreeFate) {}
    }

    private val localPath = "/home/thorsten/cyppie/projects/p1/backend"
    private fun localDetail(path: String? = localPath) =
        AgentDetail("backend", "Backend", Role.WORKER, "backend", "bash", worktreePath = path)

    // Unconfined scope → the VM's init `load()` resolves synchronously before assertions/first composition.
    private fun vm(repo: AgentManagementRepository, scope: CoroutineScope) =
        AgentSettingsViewModel("backend", repo, editable = true, initialName = "Backend", initialColorHex = null, scope = scope)

    // ---------- VM-level honesty teeth (Z1/Z2/Z4 at the state seam) ----------

    @Test
    fun load_localAgent_propagatesPath_andResolves() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val v = vm(DetailRepo(localDetail()), scope)
            assertEquals(localPath, v.state.value.worktreePath, "the typed worktreePath flows through from the DTO")
            assertTrue(v.state.value.detailResolved, "a successful detail load sets the positive resolved signal")
        } finally { scope.cancel() }
    }

    @Test
    fun load_remoteAgent_nullPath_butResolved() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val v = vm(DetailRepo(localDetail(path = null)), scope)
            assertNull(v.state.value.worktreePath)
            assertTrue(v.state.value.detailResolved, "resolved-null (a real remote agent) is DISTINCT from an unresolved load")
        } finally { scope.cancel() }
    }

    @Test
    fun load_failed_notResolved_soPanelNeverClaimsRemote() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val v = vm(FailingDetailRepo(), scope)
            assertNull(v.state.value.worktreePath, "a failed load collapses to the null default")
            assertFalse(
                v.state.value.detailResolved,
                "CYP-315 §2 honesty: a FAILED detail load must NOT resolve → the panel can't render 'not local' (failed ≠ remote)",
            )
        } finally { scope.cancel() }
    }

    // ---------- Render teeth (Z1/Z2/Z4 + copy) ----------

    @Test
    fun z1_local_rendersPathEqualToDto_withCopy_noNotLocal() = runComposeUiTest {
        val v = vm(DetailRepo(localDetail()), CoroutineScope(Dispatchers.Unconfined))
        setContent { MaterialTheme { AgentSettingsPanel(v, onDismiss = {}) } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AgentSettingsTags.PANEL).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AgentSettingsTags.WORKTREE_PATH).assertTextEquals(localPath) // rendered path == detail.worktreePath
        onNodeWithTag(AgentSettingsTags.WORKTREE_COPY).assertExists()
        onNodeWithTag(AgentSettingsTags.WORKTREE_NOT_LOCAL).assertDoesNotExist() // §8-4: exactly one status line
    }

    @Test
    fun z2_remote_showsNotLocal_noPath_noCopy() = runComposeUiTest {
        val v = vm(DetailRepo(localDetail(path = null)), CoroutineScope(Dispatchers.Unconfined))
        setContent { MaterialTheme { AgentSettingsPanel(v, onDismiss = {}) } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AgentSettingsTags.PANEL).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AgentSettingsTags.WORKTREE_NOT_LOCAL).assertExists()
        onNodeWithTag(AgentSettingsTags.WORKTREE_PATH).assertDoesNotExist()
        onNodeWithTag(AgentSettingsTags.WORKTREE_COPY).assertDoesNotExist() // nothing to copy in Z2
    }

    /**
     * The honesty invariant (§8-3): a FAILED detail load is Z4 (unresolved) → the worktree line renders NOTHING —
     * never "not local". Mutation: hang the "not local" hint on bare `worktreePath == null` (drop the `detailResolved`
     * guard) → this failed-load case would render "not local" → RED.
     */
    @Test
    fun z4_failedLoad_neverShowsNotLocal_norPath() = runComposeUiTest {
        val v = vm(FailingDetailRepo(), CoroutineScope(Dispatchers.Unconfined))
        setContent { MaterialTheme { AgentSettingsPanel(v, onDismiss = {}) } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AgentSettingsTags.PANEL).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AgentSettingsTags.WORKTREE_NOT_LOCAL).assertDoesNotExist()
        onNodeWithTag(AgentSettingsTags.WORKTREE_PATH).assertDoesNotExist()
    }

    /**
     * §8-6 (no fake "copied"): clicking copy writes the REAL path to the clipboard. Rendered via [WorktreePathSection]
     * directly (not the full panel) so a test-provided [LocalClipboardManager] reaches the click handler — the panel's
     * AlertDialog is a separate composition window that wouldn't inherit the fake. `captured` is filled synchronously in
     * the `onClick`, so it is clock-robust. (The transient INFO receipt + ~2 s self-clear are verified by construction —
     * `LaunchedEffect { delay(..); copied=false }` — and UX-QA, NOT asserted here: an idle-sync auto-advances the test
     * clock to the pending `delay` and clears the receipt before it can be observed.) Mutation: drop the
     * `clipboard.setText(..)` call → `captured` stays empty → RED.
     */
    @Test
    fun copy_writesRealPathToClipboard() = runComposeUiTest {
        val captured = mutableListOf<String>()
        val fakeClipboard = object : ClipboardManager {
            override fun setText(annotatedString: AnnotatedString) { captured.add(annotatedString.text) }
            override fun getText(): AnnotatedString? = captured.lastOrNull()?.let { AnnotatedString(it) }
        }
        val state = AgentSettingsUiState(id = "backend", editable = true, detailResolved = true, worktreePath = localPath)
        setContent {
            CompositionLocalProvider(LocalClipboardManager provides fakeClipboard) {
                MaterialTheme { Column { WorktreePathSection(state) } }
            }
        }
        waitForIdle()
        onNodeWithTag(AgentSettingsTags.WORKTREE_COPY).performClick()
        waitForIdle()
        assertEquals(listOf(localPath), captured, "the real path is written to the clipboard on copy (no fake receipt)")
    }
}
