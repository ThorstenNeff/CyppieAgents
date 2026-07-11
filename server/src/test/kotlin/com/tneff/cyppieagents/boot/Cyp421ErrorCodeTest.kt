package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ClaudeCodeSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.connector.ProcessBuilderSpawner
import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.AgentErrorCode
import com.tneff.cyppieagents.model.AgentRunState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-421 (b) — the ERROR **code** (WHY an agent is in ERROR), single-sourced in [LifecycleManager] and carried
 * on [com.tneff.cyppieagents.model.AgentRunStateEvent.errorCode]. Mirrors [LifecycleObservedExitTest]'s harness:
 * a REAL OS process, observed — NOT a command read back — so the mapping is proven against a live exit code, not
 * a fixture.
 *
 * The load-bearing guard is [deliberateStop_isStopped_withNoErrorCode]: a bidden SIGTERM stop stays STOPPED and
 * must NOT be handed a `SIGNALLED` code just because the OS reports 143 — only an UNBIDDEN death is an ERROR.
 * Mutation (map a clean exit / a stop to a code, or break [LifecycleManager.errorCodeForExit]) reddens the
 * corresponding assertion; a guard that fails with the thing it guards against proves nothing.
 */
class Cyp421ErrorCodeTest {

    private val scope = CoroutineScope(SupervisorJob())
    private val spawner = ProcessBuilderSpawner()
    private val spawned = mutableListOf<AgentProcess>()

    @AfterTest
    fun tearDown() {
        spawned.forEach { runCatching { it.destroy() } }
        scope.cancel()
    }

    private fun realProcess(script: String): AgentProcess =
        spawner.spawn(listOf("sh", "-c", script), File("."), emptyMap()).also { spawned += it }

    private fun managerOver(vararg processes: AgentProcess): LifecycleManager {
        val queue = ArrayDeque(processes.toList())
        return LifecycleManager(
            initialWorktrees = mapOf("backend" to "backend"),
            sessions = ConnectorSessions(),
            ensureWorktree = {},
            spawn = { id, _ ->
                val process = queue.removeFirst()
                ClaudeCodeSession(agentId = id, process = process, turnQueue = SessionTurnQueue(), scope = scope)
                    .also { it.start() }
            },
        ).also { it.bootAgent("backend") }
    }

    private suspend fun awaitStateChange(manager: LifecycleManager): AgentRunState? =
        withTimeoutOrNull(10_000) {
            while (manager.runStateOf("backend") == AgentRunState.RUNNING) delay(20)
            manager.runStateOf("backend")
        }

    /** The stored code, via the connect-time [LifecycleManager.snapshot] (proves it also survives a reconnect). */
    private fun errorCodeOf(manager: LifecycleManager): AgentErrorCode? =
        manager.snapshot().first { it.agentId == "backend" }.errorCode

    @Test
    fun signalDeath_isSIGNALLED() = runBlocking {
        val process = realProcess("exec sleep 5")
        val manager = managerOver(process)
        process.destroy() // SIGTERM → exit 143 (128 + 15), an UNBIDDEN death (no stop() was issued)
        assertEquals(AgentRunState.ERROR, awaitStateChange(manager))
        assertEquals(AgentErrorCode.SIGNALLED, errorCodeOf(manager), "a signalled death (143) is SIGNALLED")
    }

    @Test
    fun nonZeroNonSignalExit_isCRASHED() = runBlocking {
        val manager = managerOver(realProcess("exit 3"))
        assertEquals(AgentRunState.ERROR, awaitStateChange(manager))
        assertEquals(AgentErrorCode.CRASHED, errorCodeOf(manager), "exit 3 (non-zero, non-signal) is CRASHED")
    }

    @Test
    fun cleanExit_isStopped_withNoErrorCode() = runBlocking {
        val manager = managerOver(realProcess("exit 0"))
        assertEquals(AgentRunState.STOPPED, awaitStateChange(manager))
        assertNull(errorCodeOf(manager), "a clean STOPPED carries no errorCode")
    }

    @Test
    fun deliberateStop_isStopped_withNoErrorCode() = runBlocking {
        // THE GUARDRAIL: a bidden SIGTERM stop stays STOPPED and is NEVER given a SIGNALLED code, even though the
        // OS would report 143 — only an unbidden death is an ERROR (the closing-path returns before the exit tail).
        val manager = managerOver(realProcess("exec sleep 5"))
        val event = manager.stop("backend")
        assertEquals(AgentRunState.STOPPED, event.runState)
        assertNull(event.errorCode, "a deliberate stop is STOPPED with NO errorCode — never a SIGNALLED-ERROR")
        assertNull(errorCodeOf(manager), "and the stored snapshot carries no code either")
    }

    @Test
    fun spawnFailure_isSPAWN_FAILED() = runBlocking {
        val manager = LifecycleManager(
            initialWorktrees = mapOf("backend" to "backend"),
            sessions = ConnectorSessions(),
            ensureWorktree = {},
            spawn = { _, _ -> throw RuntimeException("boom — the message stays in the log, never the DTO") },
        )
        manager.bootAgent("backend") // catches → ERROR + SPAWN_FAILED (b1: no free-text message on the wire)
        assertEquals(AgentRunState.ERROR, manager.runStateOf("backend"))
        assertEquals(AgentErrorCode.SPAWN_FAILED, errorCodeOf(manager), "a spawn that threw is SPAWN_FAILED")
    }
}
