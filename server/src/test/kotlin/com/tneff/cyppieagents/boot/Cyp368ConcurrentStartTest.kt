package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ClaudeCodeSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.connector.ProcessBuilderSpawner
import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.routing.ConflictException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-368 — **two concurrent `POST /start` must spawn exactly one process.**
 *
 * `LifecycleManager.start()` is check-then-act: it reads the run state, throws 409 if RUNNING, and publishes
 * RUNNING only *after* `spawnFn` returns. Nothing serialises lifecycle operations — no `Mutex` in the manager,
 * none in `LifecycleRoutes` (`post("/start") { call.lifecycleAction { lifecycle().start(it) } }`), and Ktor
 * serves requests concurrently. So the race window is the **entire spawn duration** (a real `fork`/`exec`),
 * not a few instructions.
 *
 * Consequences when both callers win:
 *  - two live `claude` processes for one agent. `ConnectorSessions.register` overwrites `byAgent[id]` **without
 *    `close()`**, so the first is an orphan: never reaped, still burning tokens, still writing to the Hub under
 *    the same `agentId`;
 *  - the orphan's exit listener stays bound to that `agentId`, so **its** death later writes the run state of
 *    the session that replaced it (a live agent flips to ERROR);
 *  - two reader threads ⇒ two concurrent `onObservedExit` ⇒ its check-then-act finally has a second runner.
 *
 * **No artificial widening.** There is no `delay`/`sleep` in the spawn: a test that needs one is testing the
 * sleep. The window is `fork`/`exec`, and that is enough — an unfixed tree raced in 11 of 12 measured rounds.
 *
 * The loop is what makes this a *regression* test rather than a demonstration: once the operations are
 * serialised per agent, **every** round yields exactly one session, so the assertion is deterministic. Before
 * the fix a single round races with ~11/12 probability, so ten rounds fail with certainty (measured: round 0).
 *
 * **What this test does NOT cover** — do not read it as a proof that CYP-368 is closed:
 *  - `start ∥ stop` and `start ∥ restart` are untouched. A fix that locks only `start()` turns this green and
 *    leaves the class open.
 *  - the orphan process is visible here only as "two spawns". That it keeps running, is never reaped, and keeps
 *    writing to the Hub under the same `agentId` needs its own test.
 */
class Cyp368ConcurrentStartTest {

    private val scope = CoroutineScope(SupervisorJob())
    private val spawner = ProcessBuilderSpawner()

    /**
     * Thread-safe on purpose: [liveProcess] is called from BOTH `async(Dispatchers.IO)` coroutines below.
     * A plain `mutableListOf` loses an entry under a concurrent `+=`, and the lost entry is a `sleep 30` that
     * [tearDown] then never destroys — it survives the build. (A concurrency test that races its own bookkeeping
     * is the very class it is here to prove; it was caught in review, which is the point of review.)
     */
    private val spawned = CopyOnWriteArrayList<AgentProcess>()

    @AfterTest
    fun tearDown() {
        spawned.forEach { runCatching { it.destroy() } } // never leave a `sleep` behind on the build host
        scope.cancel()
    }

    /** `exec` matters: without it `sh` forks and keeps the stdout pipe open, so the reader never sees the end. */
    private fun liveProcess(): AgentProcess =
        spawner.spawn(listOf("sh", "-c", "exec sleep 30"), File("."), emptyMap()).also { spawned += it }

    @Test
    fun twoConcurrentStarts_spawnExactlyOneSession_theLoserGets409() = runBlocking {
        repeat(ROUNDS) { round ->
            val handedOut = CopyOnWriteArrayList<AgentProcess>() // same reason as `spawned`
            val manager = LifecycleManager(
                initialWorktrees = mapOf(AGENT to AGENT),
                sessions = ConnectorSessions(),
                ensureWorktree = {},
                spawn = { id, _ ->
                    val process = liveProcess()
                    handedOut += process
                    ClaudeCodeSession(id, process, SessionTurnQueue(), scope).also { it.start() }
                },
            )
            manager.register(AGENT, AGENT) // known + STOPPED, no session yet (the CYP-97 agent-add path)

            val outcomes = listOf(
                scope.async(Dispatchers.IO) { runCatching { manager.start(AGENT) } },
                scope.async(Dispatchers.IO) { runCatching { manager.start(AGENT) } },
            ).awaitAll()

            // ---- The invariant. It holds for EVERY correct fix; break it and the agent leaks a live process.
            assertEquals(
                1,
                handedOut.size,
                "round $round: two concurrent start() spawned ${handedOut.size} processes for one agent — " +
                    "the loser must be rejected BEFORE it spawns, not after (orphan process + a bound exit " +
                    "listener that will later write the live session's run state)",
            )
            assertEquals(
                AgentRunState.RUNNING,
                manager.runStateOf(AGENT),
                "round $round: the winner's agent must end up RUNNING",
            )

            // ---- The contract. This is a CHOICE, not an invariant: the loser is REJECTED, it does not wait.
            //
            // The alternative — the second caller blocks on the winner's spawn and returns the same RUNNING
            // event (`rejected == 0`, `successes == 2`) — is deliberately excluded, for three reasons:
            //  1. `409 already_running` is the EXISTING contract. The code exists, `AgentViewModel` already maps
            //     it, the operator UI already renders it. A waiting `start` would be a new client behaviour.
            //  2. A waiter must answer "what if the winner's spawn FAILS?". It would receive a `503 spawn_failed`
            //     for a spawn it never requested, or a RUNNING that is already ERROR. Both are lies.
            //  3. It falls out of the cheapest correct fix: serialise per agent, and the loser enters the lock
            //     after RUNNING was published — so it hits the existing `already_running` guard by itself.
            //
            // If the team ever wants the waiting variant, this assertion is the ONE line to change, knowingly.
            // The invariant above must not move with it.
            assertEquals(
                1,
                outcomes.count { it.exceptionOrNull() is ConflictException },
                "round $round: exactly one caller must lose with 409 already_running (see the note above — " +
                    "an idempotent 'wait and return RUNNING' variant would make this 0 and is excluded by choice)",
            )
            assertTrue(outcomes.count { it.isSuccess } == 1, "round $round: exactly one caller must succeed")
        }
    }

    private companion object {
        const val AGENT = "backend"

        /** One unfixed round races with ~11/12 probability; ten rounds make a red result a certainty. */
        const val ROUNDS = 10
    }
}
