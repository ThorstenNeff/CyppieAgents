package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.connector.CapabilityRegistry
import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.connector.ProviderRegistry
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CYP-247.1 (L) — the [RuntimeRegistry] resolution contract: the ONE place L makes the agent lifecycle
 * per-project. Proves active() follows the LIVE pointer (not a frozen value), of() is honest about
 * unknown/evicted projects, and — the load-bearing invariant — active() is **fail-closed** (throws rather
 * than silently returning another project's runtime, which would be the cross-project lifecycle bleed L
 * exists to prevent).
 */
class RuntimeRegistryTest {

    private val gitRoot: File = Files.createTempDirectory("runtime-reg").toFile().also { it.deleteOnExit() }
    private val noopRunner = object : CommandRunner {
        override fun run(command: List<String>, cwd: File) = CommandResult(0, "")
    }

    /** A throwaway [ProjectRuntime] with real-but-inert members (never spawned) — enough to test resolution. */
    private fun runtime(projectId: String): ProjectRuntime {
        val state = HubState.hubAndSpoke(listOf(Agent("po", "PO", Role.PO, "po")), HubState.OPERATOR_ID, projectId)
        val sessions = ConnectorSessions()
        val configs = AgentConfigRegistry()
        val lifecycle = LifecycleManager(emptyMap(), sessions, ensureWorktree = {}, spawn = { _, _ -> error("no spawn in test") })
        val mgmt = AgentManagement(state, lifecycle, configs, ensureWorktree = {}, deleteWorktree = {})
        // CYP-247.2: a WorktreeManager scoped to THIS project → worktreesRoot = <gitRoot>/projects/<projectId>.
        val worktrees = WorktreeManager(noopRunner, gitRoot, projectId)
        return ProjectRuntime(projectId, lifecycle, sessions, configs, CapabilityRegistry(), ProviderRegistry(), mgmt, worktrees)
    }

    @Test
    fun active_resolvesByLivePointer_andFollowsSwitch() {
        var active = "alpha"
        val reg = RuntimeRegistry { active }
        val a = reg.register(runtime("alpha"))
        val b = reg.register(runtime("beta"))

        assertSame(a, reg.active(), "active() resolves the alpha runtime while alpha is active")
        active = "beta" // the switch — the resolver is live, not frozen
        assertSame(b, reg.active(), "active() FOLLOWS the switched pointer (live resolver)")
        assertEquals(2, reg.liveCount())
    }

    @Test
    fun of_knownReturnsRuntime_unknownIsNull() {
        val reg = RuntimeRegistry { "alpha" }
        val a = reg.register(runtime("alpha"))
        assertSame(a, reg.of("alpha"))
        assertNull(reg.of("ghost"), "of(unknown) is null — no live runtime for a not-yet-activated/evicted project")
    }

    @Test
    fun active_worktrees_resolvePerProjectRoot_andFollowSwitch() {
        // CYP-247.2: each runtime carries a WorktreeManager scoped to its projectId; active().worktrees follows
        // the switch, so a spawn / ensureWorktree lands in projects/<active>/ — the un-pin that makes two
        // projects' agents (even same id) land in DISTINCT dirs. Mutation (a consumer captures the single boot
        // worktrees instead of active().worktrees) → the root stops following the switch → red.
        var active = "alpha"
        val reg = RuntimeRegistry { active }
        reg.register(runtime("alpha"))
        reg.register(runtime("beta"))

        assertTrue(reg.active().worktrees.worktreesRoot.path.endsWith("projects/alpha"), "active=alpha → projects/alpha root")
        active = "beta"
        assertTrue(reg.active().worktrees.worktreesRoot.path.endsWith("projects/beta"), "switch → projects/beta root (per-project)")
    }

    private class FakeSession(override val agentId: String) : ConnectorSession {
        override val events: Flow<StreamJsonEvent> = emptyFlow()
        override suspend fun sendTurn(turn: UserTurn) {}
        override fun close() {}
        override suspend fun closeAndAwait() {}
    }

    @Test
    fun perRuntimeRegistries_isolateAgentState_acrossProjects_evenSameAgentId() {
        // CYP-254 — the CORE L isolation invariant: two projects with the SAME agent id do NOT collide,
        // because each ProjectRuntime holds its OWN lifecycle + sessions maps (Arch B: structural isolation,
        // not a shared agentId-keyed map). Register `backend` in alpha's runtime; beta's runtime must not see
        // it — in either the run-state (lifecycle) or the live session. Mutation: share ONE LifecycleManager /
        // ConnectorSessions across the two runtimes → beta sees alpha's `backend` → red. This is the guard
        // that the de-singletonization (CYP-254 spine wiring) must never regress into cross-project bleed.
        val a = runtime("alpha")
        val b = runtime("beta")

        a.lifecycle.register("backend", "backend")
        assertTrue(a.lifecycle.knows("backend"), "alpha's runtime knows its own backend")
        assertFalse(b.lifecycle.knows("backend"), "beta's runtime does NOT see alpha's backend (isolated lifecycle map)")

        a.connectorSessions.register(FakeSession("backend"))
        assertNotNull(a.connectorSessions.session("backend"), "alpha's runtime holds its own backend session")
        assertNull(b.connectorSessions.session("backend"), "beta's runtime does NOT see alpha's backend session (isolated)")
    }

    @Test
    fun active_failsClosed_whenActiveProjectHasNoRuntime() {
        // active = beta, but only alpha is registered. active() MUST throw — never fall back to alpha's
        // lifecycle (that fallback would be the cross-project bleed). Mutation: return runtimes.values.first()
        // instead of throwing → this stops throwing → red.
        val reg = RuntimeRegistry { "beta" }
        reg.register(runtime("alpha"))
        assertFailsWith<IllegalStateException> { reg.active() }
    }
}
