package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ClaudeCodeSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.connector.ProcessBuilderSpawner
import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.AgentRunState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-368 — `start ∥ restart`, the pair `Cyp368ConcurrentStartTest` explicitly does **not** cover. A fix that
 * locks only `start()` turns that test green and leaves this one open, so the claim "the per-agent lock covers
 * the other transitions too" is asserted here rather than argued.
 *
 * ## This test was vacuous once, and only the mutation probe said so
 *
 * The first version began each round from a **RUNNING** agent (`manager.start()`, then race the pair). It was
 * green with the lock — and it stayed green with the lock **removed from `restart` and `stop`**. It was measuring
 * the 409 guard, not the mutex: against an already-RUNNING agent the concurrent `start()` reads RUNNING and
 * throws `already_running` before it does anything at all. The two operations were never concurrent in the only
 * sense that matters — one of them had already returned. Green, and worth nothing.
 *
 * The hazard needs a **STOPPED** agent, which is also the realistic one: the operator's double-click on a stopped
 * agent, and the CYP-97 add path (`register` leaves an agent known and STOPPED). Only there do both transitions
 * get past their guards and into a `fork/exec`.
 *
 * ## What is asserted, and why it is not the run state
 *
 * A run state can be *right by luck* while a process is orphaned — that is exactly the CYP-368 damage, and
 * asserting the state alone is how it hides. So each spawned process is wrapped ([TrackedProcess]) and the
 * invariant is about **processes**, not labels:
 *
 *  > Every process spawned this round is either the registered session's, or it has been `destroy()`ed.
 *
 * A survivor with no session is the orphan: an unregistered `claude`, never reaped, still burning tokens, still
 * holding an exit listener that will later move the run state of the session that replaced it. `runStateOf` cannot
 * see it. That assertion runs **first**, before the run-state one, so a red build names the damage rather than a
 * downstream symptom (unfixed, the `doSpawn` tripwire turns the second spawn into ERROR — a true statement about a
 * state, which tells the next reader nothing about the process still running).
 *
 * `restart` legitimately spawns a second process (it kills the old one and replaces it), so counting *spawns* —
 * right for `start ∥ start`, which `Cyp368ConcurrentStartTest` does — would be wrong here. Counting **survivors**
 * is right for every interleaving. Either ordering is legal: `start` then `restart` ⇒ two spawns, one survivor;
 * `restart` then `start` ⇒ one spawn, one survivor, and the `start` loses with 409. Two survivors is not.
 *
 * No artificial widening: real `fork/exec`s, ten rounds. **Mutation probe (run):** drop `transitions.withAgent`
 * from `restart` → red in round 0.
 *
 * ## What this does NOT cover — do not read it as "CYP-368 is closed"
 *
 * `start ∥ stop` and `restart ∥ stop` are **argued, not proven**. The lock serialises them structurally, but I
 * could not build a test that fails without it, so there is none here: a green test that cannot go red reads as
 * coverage and is worse than an admitted gap. The reason is structural, not laziness — `stop`'s window is a few
 * instructions, not a `fork/exec`. Against a RUNNING agent `stop` removes the session and awaits termination
 * *before* it writes STOPPED, so a concurrent `start` reads RUNNING and takes the 409: that pair is safe on the
 * guard, with or without the lock. Against a STOPPED agent `stop` finds no session and returns almost instantly,
 * so to produce the ghost (STOPPED with a live registered session) its final write would have to land after
 * `start`'s RUNNING write — i.e. the fast operation would have to finish last. Ten rounds never produced it, and
 * a `delay` that forced it would be a test of the `delay`.
 */
class Cyp368TransitionSerializationTest {

    private val scope = CoroutineScope(SupervisorJob())
    private val spawner = ProcessBuilderSpawner()
    private val spawned = CopyOnWriteArrayList<TrackedProcess>()

    @AfterTest
    fun tearDown() {
        spawned.forEach { runCatching { it.destroy() } } // never leave a `sleep` behind on the build host
        scope.cancel()
    }

    /**
     * Records whether the session ever tore this process down. `destroy()` is the only way a session ends its
     * process (`closeAndAwait` → `destroy` → reader flush → reap), so an undestroyed process is a live one. We ask
     * the process, not the registry — the registry is the thing that loses track.
     */
    private class TrackedProcess(private val delegate: AgentProcess) : AgentProcess {
        @Volatile var destroyed = false
            private set

        override val stdoutLines: Flow<String> get() = delegate.stdoutLines
        override suspend fun writeLine(line: String) = delegate.writeLine(line)
        override suspend fun awaitTerminated() = delegate.awaitTerminated()

        override fun destroy() {
            destroyed = true
            delegate.destroy()
        }
    }

    private fun manager(sessions: ConnectorSessions, handedOut: MutableList<TrackedProcess>) = LifecycleManager(
        initialWorktrees = mapOf(AGENT to AGENT),
        sessions = sessions,
        ensureWorktree = {},
        spawn = { id, _ ->
            // `exec`: without it `sh` forks and holds the stdout pipe open, so the reader never sees the end.
            val process = TrackedProcess(spawner.spawn(listOf("sh", "-c", "exec sleep 30"), File("."), emptyMap()))
            spawned += process // tearDown reaps it even if the round leaks it — that leak IS the bug under test
            handedOut += process
            ClaudeCodeSession(id, process, SessionTurnQueue(), scope).also { it.start() }
        },
    )

    /**
     * The precondition is STOPPED, not RUNNING (see the class KDoc): from RUNNING, `start` short-circuits on the
     * 409 guard and nothing races. From STOPPED both transitions get past their guards, and `restart`'s teardown +
     * spawn overlaps `start`'s spawn — a window a whole `fork/exec` wide.
     */
    @Test
    fun startAndRestartConcurrently_leaveExactlyOneLiveProcess() = runBlocking {
        repeat(ROUNDS) { round ->
            val handedOut = CopyOnWriteArrayList<TrackedProcess>() // written from BOTH racing coroutines
            val sessions = ConnectorSessions()
            val manager = manager(sessions, handedOut).also { it.register(AGENT, AGENT) } // known + STOPPED

            listOf(
                scope.async(Dispatchers.IO) { runCatching { manager.start(AGENT) } },
                scope.async(Dispatchers.IO) { runCatching { manager.restart(AGENT) } },
            ).awaitAll()

            val registered = sessions.agentIds().size
            val survivors = handedOut.count { !it.destroyed }
            val where = "round $round: state=${manager.runStateOf(AGENT)} registered=$registered " +
                "spawned=${handedOut.size} survivors=$survivors"

            // FIRST: the damage itself. A survivor with no session is an orphaned `claude` — unreaped, still
            // burning tokens, and its exit listener will later move the run state of the session that replaced it.
            assertEquals(
                registered, survivors,
                "$where — every spawned process must be either the registered session's or destroyed",
            )
            // THEN: the state. Both transitions end in a running agent; neither may leave it dead.
            assertEquals(AgentRunState.RUNNING, manager.runStateOf(AGENT), where)
            assertEquals(1, registered, "$where — RUNNING must have exactly one session")
        }
    }

    private companion object {
        const val AGENT = "backend"
        const val ROUNDS = 10
    }
}
