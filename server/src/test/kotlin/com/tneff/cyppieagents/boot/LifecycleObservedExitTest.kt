package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ClaudeCodeSession
import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.connector.ProcessBuilderSpawner
import com.tneff.cyppieagents.mediation.SessionTurnQueue
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
import kotlin.test.assertNotEquals

/**
 * CYP-351 — the run state must follow the **observed** process, not the last command.
 *
 * `LifecycleManager.status` was a command memory: it recorded what we asked for (`start`/`stop`/`restart`) and
 * never what happened. A `claude` that died on its own kept answering RUNNING, `/ws/lifecycle` stayed silent,
 * and the operator's Start button — the one control that would have recovered the agent — stayed disabled.
 *
 * These tests **kill a real OS process** and assert the state moves on its own. A test that issued a command
 * and then read the same map back would pull its expectation through the very seam it claims to verify: it
 * would have passed before this fix, against a manager that observes nothing.
 *
 * Mutation probe (run): remove `session.addExitListener { … }` from `LifecycleManager.doSpawn` → **four of the
 * five** go red, because the state simply stays RUNNING. The fifth, [deliberateStop_isStopped_neverError],
 * stays **green** — and must: it guards the opposite direction, that observing a death never turns an ordinary
 * Stop into an ERROR. A guard that fails with the thing it guards against proves nothing.
 */
class LifecycleObservedExitTest {

    private val scope = CoroutineScope(SupervisorJob())
    private val spawner = ProcessBuilderSpawner()
    private val spawned = mutableListOf<AgentProcess>()

    @AfterTest
    fun tearDown() {
        spawned.forEach { runCatching { it.destroy() } } // never leave a `sleep` behind on the build host
        scope.cancel()
    }

    /**
     * A real `sh` process, spawned the way the connector spawns `claude`: piped stdio, no PTY.
     *
     * `exec` matters: without it `sh` forks the child and keeps the stdout pipe open in the parent, so killing
     * the shell never closes the pipe and the reader never sees the end. That is a property of the harness,
     * not of the code under test — a lesson worth leaving in the file.
     */
    private fun realProcess(script: String): AgentProcess =
        spawner.spawn(listOf("sh", "-c", script), File("."), emptyMap()).also { spawned += it }

    /**
     * A manager whose spawn hands out the given processes in order (one per spawn/restart), each wrapped in a
     * real [ClaudeCodeSession] with its reader running — the same object the live connector registers.
     */
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

    /** Wait until the state leaves RUNNING. Nothing is commanded — it has to move by itself or not at all. */
    private suspend fun awaitStateChange(manager: LifecycleManager): AgentRunState? =
        withTimeoutOrNull(10_000) {
            while (manager.runStateOf("backend") == AgentRunState.RUNNING) delay(20)
            manager.runStateOf("backend")
        }

    @Test
    fun processKilledExternally_flipsToError_withoutAnyCommand() = runBlocking {
        // A long-lived agent, killed the way the OS kills one. We never call stop()/close(): the session is
        // not torn down, it is bereaved.
        val process = realProcess("exec sleep 5")
        val manager = managerOver(process)
        assertEquals(AgentRunState.RUNNING, manager.runStateOf("backend"))

        process.destroy() // the kill. Not a lifecycle command.

        assertEquals(AgentRunState.ERROR, awaitStateChange(manager), "a killed agent must not keep reporting RUNNING")
    }

    /**
     * **EOF is not death.** The process closes its stdout and keeps running: the reader's `collect` returns at
     * once, with no exception and no death. Anything that concludes "the agent exited" from the stream ending
     * has invented an observation.
     *
     * This is one jaw of the pincer. Its opposite is [processKilledExternally_flipsToError_withoutAnyCommand]:
     * deleting the exit tail makes THIS test pass and that one fail. There is no edit that satisfies both except
     * the correct one — confirm the death with `waitFor()` instead of inferring it from the stream.
     */
    @Test
    fun streamEndsButProcessLives_isNotADeath() = runBlocking {
        val manager = managerOver(realProcess("exec 1>&-; sleep 3")) // closes stdout, lives on
        delay(1_000) // long past the EOF, long before the process is due to exit
        assertEquals(
            AgentRunState.RUNNING, manager.runStateOf("backend"),
            "a closed stdout is the end of our hearing, not the end of the process",
        )
        // ...and when it does finally exit, cleanly, the state follows the observation.
        assertEquals(AgentRunState.STOPPED, awaitStateChange(manager), "the real exit is observed when it happens")
    }

    @Test
    fun processDiesWithNonZeroExit_flipsToError() = runBlocking {
        val manager = managerOver(realProcess("exit 3"))
        assertEquals(AgentRunState.ERROR, awaitStateChange(manager), "a crash (exit 3) is ERROR, not STOPPED")
    }

    @Test
    fun processExitsCleanly_flipsToStopped() = runBlocking {
        val manager = managerOver(realProcess("exit 0"))
        assertEquals(AgentRunState.STOPPED, awaitStateChange(manager), "a clean exit (0) is STOPPED, not ERROR")
    }

    @Test
    fun deliberateStop_isStopped_neverError() = runBlocking {
        // The guard the observation must not break: closeAndAwait cancels the reader BEFORE destroying the
        // process, so the exit tail never runs. A SIGTERM'd process reports 143 — were that to leak through,
        // every ordinary Stop would land in ERROR.
        val manager = managerOver(realProcess("exec sleep 5"))
        val event = manager.stop("backend")

        assertEquals(AgentRunState.STOPPED, event.runState)
        // Give a straggling exit signal every chance to corrupt the state, then show it did not.
        delay(300)
        assertEquals(AgentRunState.STOPPED, manager.runStateOf("backend"))
        assertNotEquals(AgentRunState.ERROR, manager.runStateOf("backend"))
    }

    /**
     * The rule that keeps the fix from becoming the bug it fixes: an **unreadable** status is not evidence of
     * death. Measured on a real process — `inputStream.close()` raises `IOException("Stream closed")` while
     * `isAlive == true` — so a session whose process cannot report a status must not move the run state. Any
     * other choice trades a missing observation for an invented one.
     *
     * Modelled by an [AgentProcess] with no observable exit status (the `awaitExitCode()` default), whose
     * stdout ends immediately — exactly what every in-memory double in this suite is.
     */
    @Test
    fun exitWithoutReadableStatus_leavesRunStateUntouched() = runBlocking {
        val statuslessProcess = object : AgentProcess {
            override val stdoutLines = kotlinx.coroutines.flow.emptyFlow<String>()
            override suspend fun writeLine(line: String) {}
            override fun destroy() {}
        }
        val manager = managerOver(statuslessProcess)

        delay(300) // the reader has long since ended; nothing may have concluded a death from it
        assertEquals(
            AgentRunState.RUNNING, manager.runStateOf("backend"),
            "an unreadable exit status must not be read as a death",
        )
    }

    /**
     * The handover window, closed deterministically instead of hoped away.
     *
     * The connector starts the process before `spawn` returns, so the agent can already be dead when the manager
     * gets it. Two orderings both lost that death: subscribing too late dropped it into an empty listener list;
     * subscribing before RUNNING was published let the "only a RUNNING agent transitions" guard discard it, and
     * RUNNING was then written over a corpse. The session double here is **already ended** when handed over —
     * no timing, no sleep — so `doSpawn` must end in ERROR and must not report RUNNING to its caller.
     *
     * Mutation: move `setRunState(RUNNING)` back after `addExitListener`, or drop the sticky replay in
     * `ClaudeCodeSession`/`ResumingSession` → red.
     */
    @Test
    fun agentThatDiesDuringTheHandover_isNeverPublishedAsRunning() = runBlocking {
        /** A session that is over before anyone can subscribe: the sticky contract of `addExitListener`. */
        val alreadyDead = object : ConnectorSession {
            override val agentId = "backend"
            override val events = kotlinx.coroutines.flow.emptyFlow<com.tneff.cyppieagents.model.StreamJsonEvent>()
            override suspend fun sendTurn(turn: com.tneff.cyppieagents.model.UserTurn) {}
            override fun close() {}
            override fun addExitListener(listener: (exitCode: Int?) -> Unit) = listener(3) // replays the end it missed
        }
        val manager = LifecycleManager(
            initialWorktrees = mapOf("backend" to "backend"),
            sessions = ConnectorSessions(),
            ensureWorktree = {},
            spawn = { _, _ -> alreadyDead },
        )

        val reported = manager.start("backend")

        assertEquals(AgentRunState.ERROR, manager.runStateOf("backend"), "a corpse must not hold a green dot")
        assertNotEquals(AgentRunState.RUNNING, reported.runState, "start() must not report RUNNING about a dead agent")
    }

    @Test
    fun deadAgentCanBeRestarted_andItsCorpseDoesNotOverwriteTheSuccessor() = runBlocking {
        // The operator-visible point of the whole ticket: a dead agent is restartable, and stays alive.
        val manager = managerOver(realProcess("exit 3"), realProcess("exec sleep 5"))
        assertEquals(AgentRunState.ERROR, awaitStateChange(manager))

        val revived = manager.restart("backend")

        assertEquals(AgentRunState.RUNNING, revived.runState)
        delay(300)
        assertEquals(
            AgentRunState.RUNNING, manager.runStateOf("backend"),
            "the exited session's listener must not drag its successor back down",
        )
    }
}
