package com.tneff.cyppieagents.agentsettings

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
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-216 — the avatar VM logic, security-first: (#3) writes only ever build a FRESH Preset and "avatar set" comes
 * from the SERVER response — a prefilled Upload is never cast/replayed into a write; the client PNG/JPG + size
 * pre-check is the honest first hint; the declared effective-stage (QA-3) is DTO-derived, not a pixel peek.
 */
class AgentSettingsAvatarViewModelTest {

    private class FakeAvatarRepo(val detail: AgentDetail) : AgentManagementRepository {
        val edits = mutableListOf<AgentEdit>()
        var uploadCalls = 0
        var clearCalls = 0
        var uploadResult: AgentDetail? = null
        override suspend fun list(): List<Agent> = emptyList()
        override suspend fun detail(id: String): AgentDetail = detail
        override suspend fun add(spec: NewAgentSpec): com.tneff.cyppieagents.model.CreatedAgent = error("unused")
        override suspend fun edit(id: String, edit: AgentEdit): Agent {
            edits.add(edit)
            return Agent(id, edit.name ?: detail.name, edit.role ?: detail.role, detail.worktree, avatar = edit.avatar) // CYP-313: null role = PRESERVE
        }
        override suspend fun remove(id: String, worktree: WorktreeFate) {}
        override suspend fun uploadAvatar(id: String, bytes: ByteArray, filename: String, mimeType: String): AgentDetail {
            uploadCalls++
            return uploadResult ?: detail
        }
        override suspend fun clearAvatar(id: String) { clearCalls++ }
    }

    private fun detail(avatar: AgentAvatar? = null) =
        AgentDetail("backend", "Backend", Role.WORKER, "backend", "bash", avatar = avatar)

    private fun vm(scope: CoroutineScope, repo: FakeAvatarRepo, editable: Boolean = true) =
        AgentSettingsViewModel("backend", repo, editable, initialName = "Backend", initialColorHex = null, scope = scope)

    @Test
    fun selectPreset_writesFreshPreset_seedIsAgentId_adoptsServerAvatar() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val repo = FakeAvatarRepo(detail())
            val v = vm(scope, repo)
            v.selectPreset("bottts")
            assertEquals(AgentAvatar.Preset("bottts", "backend"), repo.edits.single().avatar)
            assertEquals(AgentAvatar.Preset("bottts", "backend"), v.state.value.avatar) // from server response
            assertEquals("bottts", v.state.value.selectedStyle)
            assertEquals(AvatarStage.PRESET, v.state.value.effectiveStage)
        } finally { scope.cancel() }
    }

    @Test
    fun prefilledUpload_isNotReplayedIntoAWrite_selectPresetBuildsFresh() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val repo = FakeAvatarRepo(detail(avatar = AgentAvatar.Upload("srv-ref-xyz")))
            val v = vm(scope, repo) // load() prefills avatar = Upload
            assertEquals(AvatarStage.IMAGE, v.state.value.effectiveStage)
            assertNull(v.state.value.selectedStyle, "an Upload prefill selects no grid style (no as-Preset cast)")
            v.selectPreset("avataaars")
            // The write is a FRESH Preset — the prefilled Upload ref never leaks into the write path (#3).
            assertEquals(AgentAvatar.Preset("avataaars", "backend"), repo.edits.single().avatar)
        } finally { scope.cancel() }
    }

    @Test
    fun upload_valid_adoptsServerDetailAvatar_notLocalPick() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val repo = FakeAvatarRepo(detail()).apply { uploadResult = detail(avatar = AgentAvatar.Upload("server-minted")) }
            val v = vm(scope, repo)
            v.uploadAvatar(ByteArray(1000), "pic.png", "image/png")
            assertEquals(1, repo.uploadCalls)
            assertEquals(AgentAvatar.Upload("server-minted"), v.state.value.avatar) // server truth, not the local file
            assertEquals(AvatarStage.IMAGE, v.state.value.effectiveStage)
        } finally { scope.cancel() }
    }

    @Test
    fun upload_wrongType_blockedByPrecheck_noRepoCall() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val repo = FakeAvatarRepo(detail())
            val v = vm(scope, repo)
            v.uploadAvatar(ByteArray(10), "evil.svg", "image/svg+xml")
            assertEquals(AvatarUploadError.TYPE, v.state.value.avatarError)
            assertEquals(0, repo.uploadCalls, "an SVG/wrong-type must never reach the upload endpoint")
        } finally { scope.cancel() }
    }

    @Test
    fun upload_tooLarge_blockedByPrecheck() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val repo = FakeAvatarRepo(detail())
            val v = vm(scope, repo)
            v.uploadAvatar(ByteArray(AgentSettingsViewModel.MAX_UPLOAD_BYTES + 1), "big.png", "image/png")
            assertEquals(AvatarUploadError.SIZE, v.state.value.avatarError)
            assertEquals(0, repo.uploadCalls)
        } finally { scope.cancel() }
    }

    @Test
    fun clear_setsAvatarNull_viaServerConfirm() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val repo = FakeAvatarRepo(detail(avatar = AgentAvatar.Preset("bottts", "backend")))
            val v = vm(scope, repo)
            v.clearAvatar()
            assertEquals(1, repo.clearCalls)
            assertNull(v.state.value.avatar)
            assertEquals(AvatarStage.INITIALS, v.state.value.effectiveStage) // name present → initials
        } finally { scope.cancel() }
    }

    @Test
    fun nonOperator_avatarActions_areNoOps() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val repo = FakeAvatarRepo(detail())
            val v = vm(scope, repo, editable = false)
            v.selectPreset("bottts"); v.uploadAvatar(ByteArray(10), "p.png", "image/png"); v.clearAvatar()
            assertTrue(repo.edits.isEmpty() && repo.uploadCalls == 0 && repo.clearCalls == 0)
        } finally { scope.cancel() }
    }
}
