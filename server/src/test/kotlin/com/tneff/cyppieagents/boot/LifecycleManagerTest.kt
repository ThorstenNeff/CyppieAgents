package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import com.tneff.cyppieagents.routing.ConflictException
import com.tneff.cyppieagents.routing.NotFoundException
import com.tneff.cyppieagents.routing.ServiceUnavailableException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LifecycleManagerTest {

    /** Records teardown; closeAndAwait completes only when [terminate] is fired (models a dying process). */
    private class FakeSession(override val agentId: String) : ConnectorSession {
        val closedAwait = AtomicInteger(0)
        val terminate = CompletableDeferred<Unit>()
        override val events: Flow<StreamJsonEvent> = emptyFlow()
        override suspend fun sendTurn(turn: UserTurn) {}
        override fun close() {}
        override suspend fun closeAndAwait() { closedAwait.incrementAndGet(); terminate.await() }
    }

    /** The manager registers the session returned by [spawn]; the fake spawn just builds one. */
    private class Fix2(var failSpawn: Boolean = false) {
        val sessions = ConnectorSessions()
        val ensured = CopyOnWriteArrayList<String>()
        val spawnedWorktrees = CopyOnWriteArrayList<String>()
        val sessionsCreated = CopyOnWriteArrayList<FakeSession>()
        val manager = LifecycleManager(
            initialWorktrees = mapOf("backend" to "backend", "frontend" to "frontend"),
            sessions = sessions,
            ensureWorktree = { ensured.add(it) },
            spawn = { id, wt ->
                if (failSpawn) throw RuntimeException("spawn boom")
                spawnedWorktrees.add(wt)
                FakeSession(id).also { sessionsCreated.add(it) }
            },
        )
        fun terminateAll() = sessionsCreated.forEach { it.terminate.complete(Unit) }
    }

    @Test
    fun bootThenStop_thenStart_tracksStatus() = runBlocking {
        val f = Fix2()
        assertTrue(f.manager.bootAgent("backend"))
        assertEquals(AgentRunState.RUNNING, f.manager.runStateOf("backend"))
        assertEquals(listOf("backend"), f.spawnedWorktrees)

        // Stop: close the session (the fake needs a termination signal to complete the await).
        val stop = async { f.manager.stop("backend") }
        f.terminateAll()
        val stopped = stop.await()
        assertEquals(AgentRunState.STOPPED, stopped.runState)
        assertEquals(AgentRunState.STOPPED, f.manager.runStateOf("backend"))

        // Start again → RUNNING, respawned into the SAME worktree.
        assertEquals(AgentRunState.RUNNING, f.manager.start("backend").runState)
        assertEquals(listOf("backend", "backend"), f.spawnedWorktrees, "respawn reuses the same worktree")
    }

    @Test
    fun start_onAlreadyRunning_is409() = runBlocking {
        val f = Fix2()
        f.manager.bootAgent("backend")
        val e = assertFailsWith<ConflictException> { f.manager.start("backend") }
        assertEquals("already_running", e.code)
    }

    @Test
    fun unknownAgent_is404_agentNotFound() = runBlocking {
        val f = Fix2()
        val e = assertFailsWith<NotFoundException> { f.manager.stop("ghost") }
        assertEquals("agent_not_found", e.code)
    }

    @Test
    fun spawnFailure_is503_andStatusError() = runBlocking {
        val f = Fix2(failSpawn = true)
        val e = assertFailsWith<ServiceUnavailableException> { f.manager.start("backend") }
        assertEquals("spawn_failed", e.code)
        assertEquals(AgentRunState.ERROR, f.manager.runStateOf("backend"))
    }

    @Test
    fun stop_emitsStoppedOnlyAfterProcessTermination_noZombie() = runBlocking {
        val f = Fix2()
        f.manager.bootAgent("backend")
        val first = f.sessionsCreated.single()

        val stopping = async { f.manager.stop("backend") }
        // Mutation guard (stop axis): STOPPED must be emitted only AFTER the process is confirmed gone.
        // While termination is still pending, the agent must NOT yet read STOPPED — else a dying process
        // could still write to the bus after the operator was told it stopped (the zombie vector).
        yield()
        assertEquals(1, first.closedAwait.get(), "stop closes+awaits the session")
        assertEquals(
            AgentRunState.RUNNING,
            f.manager.runStateOf("backend"),
            "stop must await process termination BEFORE emitting STOPPED",
        )

        first.terminate.complete(Unit)
        assertEquals(AgentRunState.STOPPED, stopping.await().runState)
        assertEquals(AgentRunState.STOPPED, f.manager.runStateOf("backend"))
    }

    @Test
    fun restart_awaitsOldSessionDeath_beforeRespawn_noOrphan() = runBlocking {
        val f = Fix2()
        f.manager.bootAgent("backend")
        val first = f.sessionsCreated.single()

        val restart = async { f.manager.restart("backend") }
        // Mutation guard: restart must remove+await the OLD session before spawning a new one. Until the
        // old process terminates, the respawn must NOT have happened (no orphan/overlap).
        yield()
        assertEquals(1, f.sessionsCreated.size, "no respawn until the old session has terminated")
        assertEquals(1, first.closedAwait.get(), "old session is closed+awaited")

        first.terminate.complete(Unit)
        assertEquals(AgentRunState.RUNNING, restart.await().runState)
        assertEquals(2, f.sessionsCreated.size, "respawned after the old one died")
        assertEquals(listOf("backend", "backend"), f.spawnedWorktrees, "same worktree on restart")
    }

    @Test
    fun restart_spawnFailure_fallsBackToFresh_endsRunning_notError() = runBlocking {
        // CYP-330 rollback: the resume-aware `spawn` throws (models a wedged --resume respawn), but a fresh
        // (context-free) `spawnFresh` succeeds → restart must retry fresh ONCE and end RUNNING, never ERROR.
        val freshWorktrees = CopyOnWriteArrayList<String>()
        val manager = LifecycleManager(
            initialWorktrees = mapOf("backend" to "backend"),
            sessions = ConnectorSessions(),
            ensureWorktree = {},
            spawn = { _, _ -> throw RuntimeException("resume respawn boom") },
            spawnFresh = { id, wt -> freshWorktrees.add(wt); FakeSession(id) },
        )
        val ev = manager.restart("backend")
        assertEquals(AgentRunState.RUNNING, ev.runState, "restart reaches RUNNING via the fresh fallback")
        assertEquals(AgentRunState.RUNNING, manager.runStateOf("backend"))
        assertEquals(listOf("backend"), freshWorktrees, "the fresh fallback respawned into the same worktree")
    }

    @Test
    fun restart_bothSpawnAndFreshFail_is503_andError() = runBlocking {
        // If even the fresh fallback fails, the honest outcome is 503 + ERROR (genuinely un-spawnable).
        val manager = LifecycleManager(
            initialWorktrees = mapOf("backend" to "backend"),
            sessions = ConnectorSessions(),
            ensureWorktree = {},
            spawn = { _, _ -> throw RuntimeException("resume boom") },
            spawnFresh = { _, _ -> throw RuntimeException("fresh boom too") },
        )
        val e = assertFailsWith<ServiceUnavailableException> { manager.restart("backend") }
        assertEquals("spawn_failed", e.code)
        assertEquals(AgentRunState.ERROR, manager.runStateOf("backend"))
    }

    @Test
    fun restart_noFreshSeam_spawnFailure_is503_andError_legacy() = runBlocking {
        // With no spawnFresh wired (legacy/tests), a spawn failure stays the old 503 + ERROR — no silent change.
        val f = Fix2(failSpawn = true)
        val e = assertFailsWith<ServiceUnavailableException> { f.manager.restart("backend") }
        assertEquals("spawn_failed", e.code)
        assertEquals(AgentRunState.ERROR, f.manager.runStateOf("backend"))
    }

    @Test
    fun snapshot_coversAllAgents() = runBlocking {
        val f = Fix2()
        f.manager.bootAgent("backend")
        val snap = f.manager.snapshot().associate { it.agentId to it.runState }
        assertEquals(AgentRunState.RUNNING, snap["backend"])
        assertEquals(AgentRunState.STOPPED, snap["frontend"], "never-started agent reads STOPPED in the snapshot")
    }
}
