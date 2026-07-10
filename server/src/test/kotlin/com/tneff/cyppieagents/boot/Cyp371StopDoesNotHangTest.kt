package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ClaudeCodeSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.connector.ProcessBuilderSpawner
import com.tneff.cyppieagents.connector.SessionObserver
import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.SystemEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CYP-371 — **`stop()` must stop an agent, not outlive it.**
 *
 * `ClaudeCodeSession.closeAndAwait()` did `readerJob.cancelAndJoin()` **before** `process.destroy()`. The reader
 * blocks in `readLine()`, and cancelling a coroutine does not interrupt a thread parked in a blocking read: it
 * only ends at EOF, and EOF arrives when `destroy()` closes the pipe — which stood *behind* the join. Against a
 * real, currently silent `claude`, `POST /stop` never returned.
 *
 * **Each test measures the DURATION, not just the outcome.** A test that only asserted STOPPED would pass by
 * waiting the process out. The proof is deliberately two-jawed (Tester2's tongs, `qa/CYP-371-testplan`):
 *  - **T-Term:** a silent, immortal process is stopped promptly (the deadlock is gone).
 *  - **T-Flush:** a process that speaks as it dies does not lose that last line (the reader is joined, not
 *    severed — the cheap "just swap the order and keep the cancel" fix would break this).
 *
 * NB (scope): this branch is the pure deadlock fix. The invariant that a *deliberate* stop reports no death —
 * which needs the `closing` flag on top of CYP-351's `onObservedExit` — lands with CYP-351, above the CYP-368
 * mutex; it is not asserted here because `onObservedExit` does not exist yet on this base.
 */
class Cyp371StopDoesNotHangTest {

    private val scope = CoroutineScope(SupervisorJob())
    private val spawner = ProcessBuilderSpawner()
    private val spawned = CopyOnWriteArrayList<AgentProcess>()

    @AfterTest
    fun tearDown() {
        spawned.forEach { runCatching { it.destroy() } }
        scope.cancel()
    }

    /**
     * A silent agent that **cannot end by itself**: it reads stdin, prints nothing, never closes its stdout. A
     * `sleep N` would only postpone the question — a test could then not tell "terminated" from "waited it out",
     * which is exactly how this defect survived. It is also the realistic state: an agent awaiting a message
     * writes nothing, and that is precisely when `stop()` hung.
     */
    private fun silentProcess(): AgentProcess =
        spawner.spawn(listOf("sh", "-c", "while read _; do :; done"), File("."), emptyMap()).also { spawned += it }

    @Test
    fun stoppingASilentLongLivedAgent_returnsPromptly() = runBlocking {
        val sessions = ConnectorSessions()
        val manager = LifecycleManager(
            initialWorktrees = mapOf("backend" to "backend"),
            sessions = sessions,
            ensureWorktree = {},
            spawn = { id, _ ->
                ClaudeCodeSession(id, silentProcess(), SessionTurnQueue(), scope).also { it.start() }
            },
        ).also { it.bootAgent("backend") }

        val startedAt = System.nanoTime()
        val event = withTimeoutOrNull(15_000) { manager.stop("backend") }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertNotNull(event, "stop() must return; it hung for 15 s against a silent agent")
        assertEquals(AgentRunState.STOPPED, event.runState)
        assertEquals(0, sessions.agentIds().size, "the session must be gone")
        // Teardown is a destroy + an EOF + a reap. The process cannot end on its own, so there is nothing to
        // wait out: any time here is teardown time. 3 s is generous for SIGTERM + EOF + waitFor on this host.
        assertTrue(
            elapsedMs < 3_000,
            "stop() took $elapsedMs ms — it did not stop the agent, it outlived it (CYP-371)",
        )
    }

    /**
     * CYP-247 S3's promise, which the reorder must not cash in: the reader is **flushed**, not severed. What the
     * process says as it dies still arrives.
     *
     * `cancelAndJoin()` before `destroy()` deadlocked. Merely swapping the two and keeping the `cancel` would fix
     * the hang and silently break this: the cancel cuts off whatever is still in the pipe — possibly the `result`
     * of a turn that had just completed. The reader must run out on EOF. That is why `closeAndAwait` joins
     * without cancelling.
     *
     * Deterministic on purpose: a real `sh` would race — `destroy()` can kill it before it ever writes. Here the
     * process emits its final line **as it is destroyed**, precisely the case CYP-247 protects. Observed at the
     * `SessionObserver` seam, which the reader calls synchronously inside its loop — not at a replay-less flow
     * whose collector may subscribe after the emit.
     */
    @Test
    fun aLineWrittenBeforeTheStop_isNotLost() = runBlocking {
        val seen = CompletableDeferred<String>()
        val observer = object : SessionObserver {
            override fun onEvent(agentId: String, sessionId: String?, correlationId: String?, event: StreamJsonEvent) {
                if (event is SystemEvent) seen.complete(event.sessionId ?: "")
            }
            override fun onTurnStart(agentId: String, sessionId: String?, correlationId: String) {}
            override fun onProcessExit(agentId: String, sessionId: String?) {}
            override fun onStopped(agentId: String) {}
        }
        /** Writes one last line at `destroy()`, then EOFs — a process that speaks as it dies. */
        val dyingProcess = object : AgentProcess {
            private val lines = Channel<String>(Channel.UNLIMITED)
            override val stdoutLines: Flow<String> = lines.receiveAsFlow()
            override suspend fun writeLine(line: String) {}
            override fun destroy() {
                lines.trySend("""{"type":"system","subtype":"init","session_id":"s-flush"}""")
                lines.close()
            }
        }
        val session = ClaudeCodeSession("backend", dyingProcess, SessionTurnQueue(), scope, observer = observer)
        session.start()

        withTimeoutOrNull(10_000) { session.closeAndAwait() }

        assertEquals(
            "s-flush", withTimeoutOrNull(2_000) { seen.await() },
            "the reader was severed instead of flushed: the process's last line was lost (CYP-247 S3)",
        )
    }

    /**
     * CYP-371's OWN seam, not CYP-351's: a **deliberate stop is not a death**, reported at the call graph.
     *
     * The old `cancelAndJoin()` severed the reader on a stop, so its exit tail never ran. CYP-371's `destroy()`
     * → `join()` lets the reader run out on EOF — which means the tail now fires on a deliberate stop, and the
     * tail calls `observer.onProcessExit`. The production observer, [RecordingSessionObserver], records that as
     * a `process.exit` **event**. So without a guard, every stop would write a fabricated death to the Event-Log.
     *
     * This is distinct from CYP-351's T7 (a process that closes stdout but LIVES ON): that needs death OBSERVED
     * via `awaitExitCode`/`waitFor`, a deeper model change. The suppression of the tail on a stop *we* triggered
     * belongs HERE, because it is CYP-371's `destroy()`→EOF that makes the tail fire on a stop in the first place.
     *
     * Counted at the [SessionObserver] seam — the exact call the RecordingSessionObserver turns into an event.
     */
    @Test
    fun deliberateStop_reportsNoProcessExit() = runBlocking {
        val processExits = java.util.concurrent.atomic.AtomicInteger(0)
        val observer = object : SessionObserver {
            override fun onEvent(agentId: String, sessionId: String?, correlationId: String?, event: StreamJsonEvent) {}
            override fun onTurnStart(agentId: String, sessionId: String?, correlationId: String) {}
            override fun onProcessExit(agentId: String, sessionId: String?) { processExits.incrementAndGet() }
            override fun onStopped(agentId: String) {}
        }
        /** A silent process that EOFs when destroyed — the ordinary case: a `claude` idling with nothing to say. */
        val silentThenEof = object : AgentProcess {
            private val lines = Channel<String>(Channel.UNLIMITED)
            override val stdoutLines: Flow<String> = lines.receiveAsFlow()
            override suspend fun writeLine(line: String) {}
            override fun destroy() { lines.close() } // destroy → EOF, like a real process under SIGTERM
        }
        val session = ClaudeCodeSession("backend", silentThenEof, SessionTurnQueue(), scope, observer = observer)
        session.start()

        withTimeoutOrNull(10_000) { session.closeAndAwait() }

        assertEquals(
            0, processExits.get(),
            "a stop the operator asked for is not a death — but the reader ran out on our own EOF and the tail " +
                "reported onProcessExit, which RecordingSessionObserver writes as a process.exit event (CYP-371)",
        )
    }
}
