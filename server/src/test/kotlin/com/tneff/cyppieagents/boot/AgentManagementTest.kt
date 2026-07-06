package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import com.tneff.cyppieagents.model.WorktreeFate
import com.tneff.cyppieagents.routing.ConflictException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runtime agent CRUD orchestration (S14 / CYP-97): guards, add-without-spawn, edit-preserve, remove fate. */
class AgentManagementTest {

    private class FakeSession(override val agentId: String) : ConnectorSession {
        override val events: Flow<StreamJsonEvent> = emptyFlow()
        override suspend fun sendTurn(turn: UserTurn) {}
        override fun close() {}
        override suspend fun closeAndAwait() {}
    }

    private class Fix {
        val state = HubState.hubAndSpoke(
            listOf(Agent("po", "PO", Role.PO, "po"), Agent("frontend", "FE", Role.WORKER, "frontend")),
            HubState.OPERATOR_ID,
            "default",
        )
        val ensured = CopyOnWriteArrayList<String>()
        val deleted = CopyOnWriteArrayList<String>()
        val configs = AgentConfigRegistry(
            listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("frontend", "FE", Role.WORKER, claudeMd = "fe-persona")),
        )
        val lifecycle = LifecycleManager(
            initialWorktrees = mapOf("po" to "po", "frontend" to "frontend"),
            sessions = ConnectorSessions(),
            ensureWorktree = { ensured.add(it) },
            spawn = { id, _ -> FakeSession(id) },
        )
        val mgmt = AgentManagement(state, lifecycle, configs, ensureWorktree = { ensured.add(it) }, deleteWorktree = { deleted.add(it) })
    }

    // ---- CYP-246: CRUD writes into the ACTIVE project's overlay, not a boot-frozen DEFAULT ----

    @Test fun edit_persistsOverride_toActiveProject_notDefault() {
        // CYP-246: an edit's durable overlay follows the ACTIVE project (resolved live), so a rename made
        // after a switch lands in the switched project's overlay — parity with the per-project agent slice.
        // The mutant "activeProjectId → the DEFAULT constant" reddens (the override would land under default).
        val f = Fix()
        val overridesFile = java.io.File.createTempFile("cyp246-overrides", ".json").also { it.deleteOnExit() }
        val overrides = AgentOverrideStore(overridesFile)
        var active = "default"
        val mgmt = AgentManagement(
            f.state, f.lifecycle, f.configs,
            ensureWorktree = {}, deleteWorktree = {},
            overrides = overrides,
            activeProjectId = { active },
        )
        active = "beta" // switch the active project before the edit
        mgmt.edit("frontend", AgentEdit(role = Role.WORKER, name = "FE-in-beta"))
        assertEquals("FE-in-beta", overrides.allFor("beta")["frontend"]?.name, "edit writes into the ACTIVE project's overlay")
        assertNull(overrides.allFor("default")["frontend"]?.name, "and NOT into DEFAULT (the old boot-frozen constant)")
    }

    // ---- CYP-256 (.5a) D1: single-source routing — runtime-added → ProjectAgentStore, config-seeded → overlay ----

    @Test fun cyp256_runtimeAdded_persistsToStoreNotOverlay_configSeeded_toOverlay_noDoubleWrite() {
        val f = Fix()
        val store = FileProjectAgentStore(null)
        val overrides = FileAgentOverrideStore(null)
        val mgmt = AgentManagement(
            f.state, f.lifecycle, f.configs, ensureWorktree = {}, deleteWorktree = {},
            overrides = overrides, activeProjectId = { "default" }, projectAgents = store,
        )
        // A runtime-added agent → the ProjectAgentStore, NEVER the override overlay (no double-write at add).
        mgmt.add(NewAgentSpec(id = "added", name = "Added", role = Role.WORKER))
        assertTrue(store.contains("default", "added"), "runtime-added agent lives in the ProjectAgentStore")
        assertNull(overrides.overrideOf("default", "added"), "and NOT in the overlay (no double-write at add)")

        // Editing it (name + avatar) updates the STORE record only — the overlay stays untouched (D1).
        mgmt.edit("added", AgentEdit(role = Role.WORKER, name = "Renamed", avatar = com.tneff.cyppieagents.model.AgentAvatar.Preset("bottts", "x")))
        val rec = store.agentsFor("default").first { it.id == "added" }
        assertEquals("Renamed", rec.name)
        assertEquals(com.tneff.cyppieagents.model.AgentAvatar.Preset("bottts", "x"), rec.avatar)
        assertNull(overrides.overrideOf("default", "added"), "editing a runtime-added agent NEVER touches the overlay")

        // A CONFIG-SEEDED agent (frontend) routes the OTHER way: its edit lands in the overlay, never the store.
        mgmt.edit("frontend", AgentEdit(role = Role.WORKER, name = "FrontendRenamed"))
        assertFalse(store.contains("default", "frontend"), "a config-seeded agent is never put into the store")
        assertEquals("FrontendRenamed", overrides.overrideOf("default", "frontend")?.name, "its edit lands in the overlay")
    }

    // ---- add ----

    @Test fun add_createsStoppedAgent_notSpawned_andEnsuresWorktree() {
        val f = Fix()
        val a = f.mgmt.add(NewAgentSpec("backend", "Backend", Role.WORKER, persona = "be", launch = "claude")).agent
        assertEquals(AgentRunState.STOPPED, a.runState, "add does not spawn — STOPPED (start is CYP-73)")
        assertTrue(f.state.agents.any { it.id == "backend" })
        assertTrue(f.lifecycle.knows("backend"))
        assertEquals(AgentRunState.STOPPED, f.lifecycle.runStateOf("backend"))
        assertTrue(f.ensured.contains("backend"), "worktree ensured for the new agent")
        assertEquals("be", f.configs.personaOf("backend"))
    }

    @Test fun add_duplicate_agentExists() {
        val f = Fix()
        assertEquals("agent_exists", assertFailsWith<ConflictException> { f.mgmt.add(NewAgentSpec("frontend", "X", Role.WORKER)) }.code)
    }

    @Test fun add_secondPo_poAlreadyExists() {
        val f = Fix()
        assertEquals("po_already_exists", assertFailsWith<ConflictException> { f.mgmt.add(NewAgentSpec("po2", "X", Role.PO)) }.code)
    }

    // ---- edit (preserve on blank) ----

    @Test fun edit_blankPersona_preservesStored() {
        val f = Fix()
        f.mgmt.edit("frontend", AgentEdit(Role.WORKER, persona = null, launch = "  "))
        // Mutation: a blank→null clear would wipe these → assertions red.
        assertEquals("fe-persona", f.configs.personaOf("frontend"), "omitted persona preserves the stored value")
        assertEquals("claude", f.configs.configOf("frontend")?.launch, "blank launch preserves the stored value")
    }

    @Test fun edit_newPersona_replaces() {
        val f = Fix()
        f.mgmt.edit("frontend", AgentEdit(Role.WORKER, persona = "updated"))
        assertEquals("updated", f.configs.personaOf("frontend"))
    }

    // ---- remove ----

    @Test fun remove_keepWorktree_dropsAgentAndSpoke_keepsWorktree() = runBlocking {
        val f = Fix()
        f.mgmt.remove("frontend", WorktreeFate.KEEP)
        assertFalse(f.state.agents.any { it.id == "frontend" })
        assertNull(f.state.channels.firstOrNull { it.id == "po-frontend" }, "spoke removed (clean)")
        assertFalse(f.lifecycle.knows("frontend"))
        assertTrue(f.deleted.isEmpty(), "KEEP must NOT delete the worktree")
    }

    @Test fun remove_deleteWorktree_callsDelete() = runBlocking {
        val f = Fix()
        f.mgmt.remove("frontend", WorktreeFate.DELETE)
        assertEquals(listOf("frontend"), f.deleted, "DELETE removes the worktree (branch is kept, not this layer's job)")
    }

    @Test fun remove_onlyPo_lastPo_nothingMutated() = runBlocking {
        val f = Fix()
        assertEquals("last_po", assertFailsWith<ConflictException> { f.mgmt.remove("po", WorktreeFate.DELETE) }.code)
        assertTrue(f.state.agents.any { it.id == "po" }, "fail-closed: a rejected remove mutates nothing")
        assertTrue(f.deleted.isEmpty())
    }
}
