package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.mediation.SessionTurnQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-362 — the OTHER unbounded wait in [ClaudeCodeSession.closeAndAwait], the one CYP-374's reader-flush-SIGKILL
 * does NOT cover. After the reader has flushed, `closeAndAwait` confirms the process is gone. CYP-374's escalation
 * only fires when the reader can't drain (SIGTERM ignored + stdout held OPEN). A process that **closes its stdout**
 * — so the reader DRAINS and the flush join SUCCEEDS, no SIGKILL — but **stays alive**
 * (`sh -c 'exec 1>&-; sleep infinity'`) parks the termination confirm forever. That unbounded wait is what hung
 * [com.tneff.cyppieagents.remote.BridgeLazyInitE2eTest] indefinitely (via `relay.close()`).
 *
 * The fix uses a REAL JDK-bounded confirm (`process.waitFor(t, MS)` behind `awaitTerminated(timeoutMs)`), NOT a
 * `withTimeoutOrNull { awaitTerminated() }` — the no-arg confirm is a NON-cancellable blocking call, so a
 * coroutine timeout cannot unblock it (an earlier attempt did exactly that and was false-green). On timeout it
 * escalates `destroy()`→`destroyForcibly()` (SIGKILL), waits once more bounded, then abandons the wait loudly.
 *
 * **Production-faithful fake:** [ClosedStdoutButAliveProc]'s termination confirm is a NON-cancellable blocking
 * wait ([CountDownLatch.await], mirroring `Process.waitFor()`) — NOT a cancellable `CompletableDeferred.await()`.
 * The bounded overload models `Process.waitFor(t, MS)` honestly. The class [Timeout] rule bounds THIS test so the
 * mutation reddens (interrupt) instead of hanging >2 min — a coroutine `withTimeout` around a non-cancellable
 * blocking confirm cannot itself terminate the test.
 *
 * Mutation: point `closeAndAwait` back at the no-arg unbounded `awaitTerminated()` → it parks forever on the
 * latch (SIGKILL never reached) → `closeAndAwait` never returns → the [Timeout] rule fails this test.
 */
class Cyp362TerminationTimeoutTest {

    @get:Rule val timeout: Timeout = Timeout.seconds(20)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    /**
     * A process that **closes its stdout on SIGTERM** (so the reader drains → the flush join SUCCEEDS, no CYP-374
     * SIGKILL) but **stays alive** until SIGKILL. Its termination confirm is a real non-cancellable blocking wait
     * on a [CountDownLatch] (production-faithful — `Process.waitFor()` is not coroutine-cancellable); the latch is
     * counted down only by [destroyForcibly]. The bounded overload models `Process.waitFor(t, MS)`.
     */
    private class ClosedStdoutButAliveProc : AgentProcess {
        private val lines = Channel<String>(Channel.UNLIMITED)
        private val terminated = CountDownLatch(1)
        @Volatile var forciblyKilled = false
            private set
        override val stdoutLines: Flow<String> = lines.receiveAsFlow()
        suspend fun feed(line: String) = lines.send(line)
        override suspend fun writeLine(line: String) {}
        override fun destroy() { lines.close() }                 // SIGTERM: stdout closes → reader drains, flush SUCCEEDS
        override fun destroyForcibly() { forciblyKilled = true; terminated.countDown() } // SIGKILL: the process finally dies
        // Non-cancellable blocking confirm, like Process.waitFor() — a withTimeoutOrNull around this would NOT bound it.
        override suspend fun awaitTerminated() = withContext(Dispatchers.IO) { terminated.await() }
        // Real bounded confirm, like Process.waitFor(t, MS): returns whether it terminated within the deadline.
        override suspend fun awaitTerminated(timeoutMs: Long): Boolean =
            withContext(Dispatchers.IO) { terminated.await(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    private fun initLine(sid: String) = """{"type":"system","subtype":"init","session_id":"$sid"}"""

    private fun buildSession(proc: ClosedStdoutButAliveProc): ClaudeCodeSession =
        ClaudeCodeSession(
            agentId = "dev",
            process = proc,
            turnQueue = SessionTurnQueue(),
            scope = scope,
            onBind = {},
            readerFlushTimeoutMs = 200L, // short so the termination timeout fires fast in the test
        ).also { it.start() }

    @Test
    fun closedStdoutButAlive_closeAndAwait_boundsTerminationWait_andEscalatesToSigkill() = runBlocking {
        val proc = ClosedStdoutButAliveProc()
        val session = buildSession(proc)
        proc.feed(initLine("s1")) // a live, bound session

        // closeAndAwait: destroy() closes stdout → the reader drains → the flush join SUCCEEDS (NOT the CYP-374
        // path). THEN the termination confirm would block forever (the process is still alive). CYP-362 bounds it
        // with the JDK deadline: the 200 ms bounded confirm returns false → escalate to destroyForcibly()
        // (SIGKILL) → the process terminates → the second bounded confirm returns true. The withTimeout proves
        // closeAndAwait does not hang on the HAPPY path; the class Timeout rule catches a regression that does.
        withTimeout(10_000) { session.closeAndAwait() }

        assertTrue(proc.forciblyKilled, "CYP-362: an unbounded termination confirm must escalate to SIGKILL, not park on waitFor() forever")
    }
}
