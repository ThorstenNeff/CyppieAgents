package com.tneff.cyppieagents.agentsettings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.agentmgmt.AgentManagementRepository
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentAvatar
import com.tneff.cyppieagents.model.AgentDetail
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.WorktreeFate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test

/**
 * CYP-216 §3 avatar-section render invariants (QA-3 stage semantics, QA-7 addressable credit lines, preset selection,
 * honest upload error, operator gate). LocalAvatar* are unprovided here → no network; the grid shows placeholders.
 */
@OptIn(ExperimentalTestApi::class)
class AgentAvatarSectionTest {

    private class FakeRepo(private val detail: AgentDetail) : AgentManagementRepository {
        override suspend fun list(): List<Agent> = emptyList()
        override suspend fun detail(id: String): AgentDetail = detail
        override suspend fun add(spec: NewAgentSpec): Agent = error("unused")
        override suspend fun edit(id: String, edit: AgentEdit): Agent =
            Agent(id, detail.name, detail.role, detail.worktree, avatar = edit.avatar)
        override suspend fun remove(id: String, worktree: WorktreeFate) {}
    }

    private fun detail(avatar: AgentAvatar? = null) =
        AgentDetail("backend", "Backend", Role.WORKER, "backend", "bash", avatar = avatar)

    private fun vm(editable: Boolean, avatar: AgentAvatar? = null) = AgentSettingsViewModel(
        "backend", FakeRepo(detail(avatar)), editable, initialName = "Backend", initialColorHex = null,
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    private fun stageIs(stage: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, stage)

    @Test
    fun operator_section_stageSemantics_gridCells_creditLines_present() = runComposeUiTest {
        val v = vm(editable = true)
        setContent { MaterialTheme { AgentSettingsPanel(v, onDismiss = {}) } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AgentSettingsTags.PANEL).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithTag(AgentSettingsTags.AVATAR_SECTION).assertExists()
        // QA-3: no avatar + a name → the declared effective stage is INITIALS (deterministic, no pixel peek).
        onNodeWithTag(AgentSettingsTags.AVATAR_CURRENT).assert(stageIs("initials"))
        AVATAR_STYLES.forEach { onNodeWithTag(AgentSettingsTags.avatarPresetStyle(it.style)).assertExists() }
        onNodeWithTag(AgentSettingsTags.AVATAR_UPLOAD).assertExists()
        // QA-7 (UX-QA③): ALL 5 styles carry an addressable credit line — the free ones as honest courtesy credits.
        AVATAR_STYLES.forEach { onNodeWithTag(AgentSettingsTags.avatarCreditEntry(it.style)).assertExists() }
        onNodeWithTag(AgentSettingsTags.avatarCreditEntry("bottts")).assertExists() // free style now credited too
        // UX-QA①: the licence URI is actually RENDERED (CC BY 4.0 §3(a)) — assert the unique bottts URL (same
        // code path renders info.licenseUrl for every style, incl. the 3 shared CC-BY URIs).
        onNodeWithText("https://bottts.com/").assertExists()
    }

    @Test
    fun stageSemantics_image_whenAvatarIsUpload() = runComposeUiTest {
        val v = vm(editable = true, avatar = AgentAvatar.Upload("ref123"))
        setContent { MaterialTheme { AgentSettingsPanel(v, onDismiss = {}) } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AgentSettingsTags.PANEL).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AgentSettingsTags.AVATAR_CURRENT).assert(stageIs("image"))
    }

    @Test
    fun presetSelect_marksCellSelected() = runComposeUiTest {
        val v = vm(editable = true)
        setContent { MaterialTheme { AgentSettingsPanel(v, onDismiss = {}) } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AgentSettingsTags.PANEL).fetchSemanticsNodes().isNotEmpty() }
        // CYP-310: the live-CLAUDE.md section (field + overwrite button + note) makes the dialog taller → the preset
        // grid can sit below the fold; scroll it into view before clicking (the click must land on the real node).
        onNodeWithTag(AgentSettingsTags.avatarPresetStyle("bottts")).performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag(AgentSettingsTags.avatarPresetStyle("bottts")).assertIsSelected()
        onNodeWithTag(AgentSettingsTags.AVATAR_CURRENT).assert(stageIs("preset")) // now a Preset avatar
    }

    @Test
    fun uploadPrecheck_wrongType_showsErrorHint() = runComposeUiTest {
        val v = vm(editable = true)
        setContent { MaterialTheme { AgentSettingsPanel(v, onDismiss = {}) } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AgentSettingsTags.PANEL).fetchSemanticsNodes().isNotEmpty() }
        v.uploadAvatar(ByteArray(10), "evil.svg", "image/svg+xml"); waitForIdle()
        onNodeWithTag(AgentSettingsTags.AVATAR_UPLOAD_ERROR).assertExists()
    }

    @Test
    fun nonOperator_gridAndUploadAbsent_currentAndCreditsPresent() = runComposeUiTest {
        val v = vm(editable = false)
        setContent { MaterialTheme { AgentSettingsPanel(v, onDismiss = {}) } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AgentSettingsTags.PANEL).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AgentSettingsTags.AVATAR_CURRENT).assertExists()      // read-only view keeps the current avatar
        onNodeWithTag(AgentSettingsTags.AVATAR_CREDITS).assertExists()       // + credits
        onNodeWithTag(AgentSettingsTags.AVATAR_UPLOAD).assertDoesNotExist()  // but no edit affordances
        onNodeWithTag(AgentSettingsTags.avatarPresetStyle("bottts")).assertDoesNotExist()
    }
}
