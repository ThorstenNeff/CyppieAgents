package com.tneff.cyppieagents.agentsettings

import com.tneff.cyppieagents.agentmgmt.AgentManagementRepository
import com.tneff.cyppieagents.agentmgmt.AgentMgmtException
import com.tneff.cyppieagents.model.AgentMgmtGuard
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
 * CYP-310 — the settings VM after the CLAUDE.md field became the LIVE worktree file (its own GET/POST):
 *  - `save()` writes name+colour ONLY (persona no longer rides the shared edit path).
 *  - the field reads the live file (fail-closed on a read error — failed ≠ empty), dirty on edit;
 *  - a hard "Überschreiben" (POST + `expectedVersion`) → 200 arms the restart hint / 409 opens the conflict /
 *    other failure stays dirty (no fake "saved"); a settled-absent file is the EMPTY state.
 */
class AgentSettingsViewModelTest {

    private class FakeRepo(private val detail: AgentDetail) : AgentManagementRepository {
        val edits = mutableListOf<Pair<String, AgentEdit>>()
        override suspend fun list(): List<Agent> = emptyList()
        override suspend fun detail(id: String): AgentDetail = detail
        override suspend fun add(spec: NewAgentSpec): Agent = error("unused")
        override suspend fun edit(id: String, edit: AgentEdit): Agent {
            edits.add(id to edit)
            return Agent(id, edit.name ?: detail.name, edit.role ?: detail.role, detail.worktree) // CYP-313: null role = PRESERVE
        }
        override suspend fun remove(id: String, worktree: WorktreeFate) {}
    }

    /**
     * Controllable live-CLAUDE.md fake — a FAITHFUL model of the server's null-semantics + if-match guard: an
     * ABSENT file reports `version=null` (not a fake "v0"), and a write enforces `expectedVersion == currentVersion`
     * (absent ⇒ current=null ⇒ a first write MUST send `null`). `updateThrows` forces a specific error BEFORE the
     * guard (for the non-stale S6 path). This enforcement is what makes the first-write conditional a real tooth:
     * if the VM sent `""` for an absent file, the guard would 409 and the create test would go RED.
     */
    private class FakeClaudeMd(
        var content: String = "",
        var exists: Boolean = false,
        var version: String? = null,
        var getThrows: String? = null,
        var updateThrows: String? = null,
    ) : ClaudeMdApi {
        val updates = mutableListOf<Triple<String, String, String?>>() // (id, content, expectedVersion)
        var getCount = 0
        private fun currentVersion(): String? = if (exists) version else null
        override suspend fun get(agentId: String): ClaudeMdView {
            getCount++
            getThrows?.let { throw ClaudeMdException(it) }
            return ClaudeMdView(agentId, content, exists, currentVersion())
        }
        override suspend fun update(agentId: String, content: String, expectedVersion: String?): ClaudeMdView {
            updates.add(Triple(agentId, content, expectedVersion))
            updateThrows?.let { throw ClaudeMdException(it) }
            if (expectedVersion != currentVersion()) throw ClaudeMdException("claude_md_stale")
            this.content = content; this.exists = true; this.version = "vNext"
            return ClaudeMdView(agentId, content, true, version)
        }
    }

    private val detail = AgentDetail("backend", "Backend", Role.WORKER, "backend", "bash", persona = "vestigial", color = null)

    private fun vm(scope: CoroutineScope, editable: Boolean = true, claude: FakeClaudeMd = FakeClaudeMd()) =
        AgentSettingsViewModel("backend", FakeRepo(detail).also { repo = it }, editable, "Backend", null, claudeMdApi = claude, scope = scope)

    private lateinit var repo: FakeRepo

    @Test
    fun save_writesNameAndColour_only_noPersona() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val v = vm(scope)
            v.setName("Backend-2"); v.setColorHex("#3B82F6")
            v.save()
            val (id, edit) = repo.edits.single()
            assertEquals("backend", id)
            assertEquals("Backend-2", edit.name)
            assertEquals("#3B82F6", edit.color)
            assertNull(edit.role, "CYP-313: a display-only save sends role=null (PRESERVE) — never s.role, which the guard would read as an explicit role change")
            assertNull(edit.persona, "CYP-310: save() no longer sends persona — CLAUDE.md is its own endpoint")
            assertTrue(v.state.value.saved)
        } finally { scope.cancel() }
    }

    /**
     * CYP-313 — a colour edit of the SOLE PO succeeds against the REAL server guard ([AgentMgmtGuard], Backend's
     * `Cyp313RoleOptionalEditE2eTest` is the server-side twin). The fix: a display-only [AgentSettingsViewModel.save]
     * sends `role = null` (PRESERVE) → the guard SHORT-CIRCUITS (`edit.role ?: return null`) and never consults the
     * PO-topology → no false `last_po`. The fake repo runs the real guard against a sole-PO world; `detail` returns
     * WORKER, modelling the reported state-population gap (a fresh PO whose client role-state isn't yet PO — flagged
     * to the PO). Mutation `role = s.role` (=WORKER) → guard `last_po` → error → RED (caught by the guard AND the
     * `role == null` assertion).
     */
    @Test
    fun save_soleP0_colourEdit_preservesRole_realGuard_noLastPo() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val po = Agent("po", "PO", Role.PO, "po")
            val recorded = mutableListOf<AgentEdit>()
            val repo = object : AgentManagementRepository {
                override suspend fun list(): List<Agent> = listOf(po)
                override suspend fun detail(id: String): AgentDetail = AgentDetail("po", "PO", Role.WORKER, "po", "bash")
                override suspend fun add(spec: NewAgentSpec): Agent = error("unused")
                override suspend fun edit(id: String, edit: AgentEdit): Agent {
                    recorded.add(edit)
                    AgentMgmtGuard.validateEdit(listOf(po), id, edit)?.let { throw AgentMgmtException(it) } // REAL guard
                    return Agent(id, edit.name ?: po.name, edit.role ?: po.role, po.worktree) // null role = PRESERVE
                }
                override suspend fun remove(id: String, worktree: WorktreeFate) {}
            }
            val v = AgentSettingsViewModel("po", repo, editable = true, "PO", null, claudeMdApi = FakeClaudeMd(), scope = scope)
            v.setColorHex("#3B82F6")
            v.save()
            assertNull(recorded.single().role, "display-only save must send role=null (PRESERVE), not s.role")
            assertFalse(v.state.value.error, "the sole PO's colour edit must NOT false-positive last_po")
            assertTrue(v.state.value.saved)
        } finally { scope.cancel() }
    }

    /**
     * CYP-313 — the same PRESERVE guarantee for an AVATAR change (`writeAvatarPreset`): a preset pick on the sole PO
     * sends `role = null` → real guard OK → no `last_po`. Mutation `role = _state.value.role` (=WORKER) → `last_po`
     * → avatarError → RED.
     */
    @Test
    fun avatarPreset_soleP0_sendsRoleNull_realGuard_noLastPo() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val po = Agent("po", "PO", Role.PO, "po")
            val recorded = mutableListOf<AgentEdit>()
            val repo = object : AgentManagementRepository {
                override suspend fun list(): List<Agent> = listOf(po)
                override suspend fun detail(id: String): AgentDetail = AgentDetail("po", "PO", Role.WORKER, "po", "bash")
                override suspend fun add(spec: NewAgentSpec): Agent = error("unused")
                override suspend fun edit(id: String, edit: AgentEdit): Agent {
                    recorded.add(edit)
                    AgentMgmtGuard.validateEdit(listOf(po), id, edit)?.let { throw AgentMgmtException(it) }
                    return Agent(id, edit.name ?: po.name, edit.role ?: po.role, po.worktree)
                }
                override suspend fun remove(id: String, worktree: WorktreeFate) {}
            }
            val v = AgentSettingsViewModel("po", repo, editable = true, "PO", null, claudeMdApi = FakeClaudeMd(), scope = scope)
            v.selectPreset("bottts")
            assertNull(recorded.single().role, "avatar edit must send role=null (PRESERVE)")
            assertNull(v.state.value.avatarError, "the sole PO's avatar pick must NOT false-positive last_po")
        } finally { scope.cancel() }
    }

    @Test
    fun invalidHex_blocksSave() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val v = vm(scope)
            v.setColorHex("not-a-hex")
            assertTrue(v.state.value.hexError)
            v.save()
            assertTrue(repo.edits.isEmpty(), "an invalid hex must never be saved")
        } finally { scope.cancel() }
    }

    @Test
    fun claudeMd_loadsLiveFile_dirtyOnEdit_enablesOverwrite() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val v = vm(scope, claude = FakeClaudeMd(content = "live persona", exists = true, version = "v7"))
            val s0 = v.state.value
            assertEquals("live persona", s0.claudeMd, "field = the LIVE file, not d.persona")
            assertFalse(s0.claudeMdDirty); assertFalse(s0.claudeMdEmpty); assertFalse(s0.canOverwriteClaudeMd)
            v.setClaudeMd("edited")
            assertTrue(v.state.value.claudeMdDirty)
            assertTrue(v.state.value.canOverwriteClaudeMd, "dirty + settled read → overwrite enabled")
        } finally { scope.cancel() }
    }

    @Test
    fun claudeMd_loadFailure_failClosed_notEmpty_overwriteLocked() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val v = vm(scope, claude = FakeClaudeMd(getThrows = "agent_not_local"))
            val s = v.state.value
            assertTrue(s.claudeMdError, "a live-read failure is the fail-closed ERROR state (S5)…")
            assertFalse(s.claudeMdEmpty, "…NOT the empty state — failed ≠ empty (CYP-288 line)")
            assertFalse(s.canOverwriteClaudeMd, "never overwrite from an unknown base (fail-closed)")
        } finally { scope.cancel() }
    }

    @Test
    fun overwrite_success_writesFile_armsRestartHint_clearsDirty() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val claude = FakeClaudeMd(content = "base", exists = true, version = "v1")
            val v = vm(scope, claude = claude)
            v.setClaudeMd("new content")
            v.overwriteClaudeMd()
            assertEquals(Triple("backend", "new content", "v1"), claude.updates.single(), "POST carries the read base version")
            val s = v.state.value
            assertEquals("new content", s.claudeMd); assertFalse(s.claudeMdDirty, "dirty→clean after write")
            assertTrue(s.needsRestart, "the restart hint arms (file ≠ running agent, Hop ②)")
            assertFalse(s.claudeMdStale); assertFalse(s.claudeMdWriteError)
        } finally { scope.cancel() }
    }

    @Test
    fun overwrite_stale409_opensConflict_staysDirty_noFakeSaved() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val claude = FakeClaudeMd(content = "base", exists = true, version = "v1", updateThrows = "claude_md_stale")
            val v = vm(scope, claude = claude)
            v.setClaudeMd("mine")
            v.overwriteClaudeMd()
            val s = v.state.value
            assertTrue(s.claudeMdStale, "409 → the Layer-2 conflict dialog")
            assertFalse(s.needsRestart, "no restart hint on a conflict — nothing was written")
            assertTrue(s.claudeMdDirty, "the buffer stays dirty (no data loss)")
        } finally { scope.cancel() }
    }

    @Test
    fun overwrite_otherError_staysDirty_noRestartHint_s6() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val claude = FakeClaudeMd(content = "base", exists = true, version = "v1", updateThrows = "server_error")
            val v = vm(scope, claude = claude)
            v.setClaudeMd("mine")
            v.overwriteClaudeMd()
            val s = v.state.value
            assertTrue(s.claudeMdWriteError, "a non-stale write failure = S6")
            assertFalse(s.needsRestart, "no fake 'saved' — the write failed")
            assertTrue(s.claudeMdDirty, "stays dirty for retry")
        } finally { scope.cancel() }
    }

    @Test
    fun emptyFile_isEmptyState_firstWriteCreates_noConflict() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val claude = FakeClaudeMd(content = "", exists = false, version = null) // absent file → version=null
            val v = vm(scope, claude = claude)
            assertTrue(v.state.value.claudeMdEmpty, "settled-absent = the EMPTY state (S4), not an error")
            v.setClaudeMd("first persona")
            v.overwriteClaudeMd() // first write CREATES — no confirm, no stale
            // CYP-310 first-write tooth: the POST for an absent file carries expectedVersion=NULL (not "") — the
            // server's "expect absent" create-branch. Sending "" here 409-loops every new agent (the NO-GO bug).
            assertNull(claude.updates.single().third, "first write on an absent file sends expectedVersion=null")
            val s = v.state.value
            assertFalse(s.claudeMdStale, "no 409 loop — a first write on absent creates, never conflicts")
            assertTrue(s.claudeMdFileExists); assertTrue(s.needsRestart)
        } finally { scope.cancel() }
    }

    @Test
    fun forceOverwrite_reFetchesFreshVersion_thenWrites() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val claude = FakeClaudeMd(content = "base", exists = true, version = "v1", updateThrows = "claude_md_stale")
            val v = vm(scope, claude = claude)
            v.setClaudeMd("mine")
            v.overwriteClaudeMd() // → 409 stale (external change)
            assertTrue(v.state.value.claudeMdStale)
            // The external change is now version "v9"; the user chooses [Trotzdem überschreiben] → re-fetch + force.
            claude.version = "v9"; claude.updateThrows = null
            val getsBefore = claude.getCount
            v.forceOverwriteClaudeMd()
            assertEquals(getsBefore + 1, claude.getCount, "force re-fetches the CURRENT version first")
            assertEquals("v9", claude.updates.last().third, "…then overwrites against the FRESH version")
            assertFalse(v.state.value.claudeMdStale); assertTrue(v.state.value.needsRestart)
        } finally { scope.cancel() }
    }

    @Test
    fun nonOperator_settersAndOverwrite_areNoOps_failClosed() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val claude = FakeClaudeMd(content = "live", exists = true, version = "v1")
            val v = vm(scope, editable = false, claude = claude)
            v.setName("hacked"); v.setColorHex("#000000"); v.setClaudeMd("x")
            assertEquals("Backend", v.state.value.name)
            assertEquals("live", v.state.value.claudeMd, "non-operator setClaudeMd is a no-op")
            v.overwriteClaudeMd()
            assertTrue(claude.updates.isEmpty(), "non-operator cannot overwrite (fail-closed; server also 403s)")
            v.save(); assertTrue(repo.edits.isEmpty())
        } finally { scope.cancel() }
    }
}
