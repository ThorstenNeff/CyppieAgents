package com.tneff.cyppieagents.agentsettings

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
import kotlin.test.assertTrue

/**
 * CYP-211 — the settings VM honesty logic: one save writes name+colour+persona through the SAME `AgentEdit`
 * edit path (no 2nd persona editor, §1); the persona-change flag drives the restart hint (name/colour never do);
 * an invalid hex blocks Save; a non-operator's setters are no-ops (fail-closed).
 */
class AgentSettingsViewModelTest {

    private class FakeRepo(private val detail: AgentDetail) : AgentManagementRepository {
        val edits = mutableListOf<Pair<String, AgentEdit>>()
        override suspend fun list(): List<Agent> = emptyList()
        override suspend fun detail(id: String): AgentDetail = detail
        override suspend fun add(spec: NewAgentSpec): Agent = error("unused")
        override suspend fun edit(id: String, edit: AgentEdit): Agent {
            edits.add(id to edit)
            return Agent(id, edit.name ?: detail.name, edit.role, detail.worktree)
        }
        override suspend fun remove(id: String, worktree: WorktreeFate) {}
    }

    private val detail = AgentDetail("backend", "Backend", Role.WORKER, "backend", "bash", persona = "old persona", color = null)

    private fun vm(scope: CoroutineScope, editable: Boolean = true) =
        AgentSettingsViewModel("backend", FakeRepo(detail).also { repo = it }, editable, initialName = "Backend", initialColorHex = null, scope = scope)

    private lateinit var repo: FakeRepo

    @Test
    fun save_writesName_color_persona_throughAgentEdit_rolePreserved() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val v = vm(scope)
            v.setName("Backend-2"); v.setColorHex("#3B82F6"); v.setPersona("new persona")
            v.save()
            val (id, edit) = repo.edits.single()
            assertEquals("backend", id)
            assertEquals("Backend-2", edit.name)
            assertEquals("#3B82F6", edit.color)
            assertEquals("new persona", edit.persona)
            assertEquals(Role.WORKER, edit.role) // role carried from the prefilled detail, id never sent
            assertTrue(v.state.value.saved)
        } finally { scope.cancel() }
    }

    @Test
    fun invalidHex_blocksSave_andFlagsError() {
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
    fun needsRestart_trueAfterPersonaSave_notWhileEditing() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val v = vm(scope) // load() prefills active persona = "old persona" under Unconfined
            assertFalse(v.state.value.needsRestart)
            // UX-QA fix: editing the persona is NOT yet a "Gespeichert" state → no restart hint pre-save.
            v.setPersona("changed"); assertFalse(v.state.value.needsRestart, "unsaved persona edit → no hint")
            // Only after the SAVE (stored persona ≠ active) is the restart hint true.
            v.save(); assertTrue(v.state.value.needsRestart, "post-save saved≠active → restart hint")
        } finally { scope.cancel() }
    }

    @Test
    fun nameOrColorSave_neverTriggersRestartHint() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val v = vm(scope)
            v.setName("Renamed"); v.setColorHex("#3B82F6"); v.save()
            assertFalse(v.state.value.needsRestart, "name/colour are immediate → no restart hint even after save")
        } finally { scope.cancel() }
    }

    @Test
    fun nonOperator_settersAreNoOps_failClosed() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val v = vm(scope, editable = false)
            v.setName("hacked"); v.setColorHex("#000000"); v.setPersona("x")
            assertEquals("Backend", v.state.value.name)
            assertEquals("old persona", v.state.value.persona)
            v.save(); assertTrue(repo.edits.isEmpty())
        } finally { scope.cancel() }
    }
}
