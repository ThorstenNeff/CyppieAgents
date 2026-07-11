package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.mediation.SessionTurnQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-362 — the OTHER unbounded wait in [ClaudeCodeSession.closeAndAwait], the one CYP-374's reader-flush-SIGKILL
 * does NOT cover. After the reader has flushed, `closeAndAwait` calls `process.awaitTerminated()` = `Process.
 * waitFor()` with **no timeout**. CYP-374's escalation only fires when the reader can't drain (SIGTERM ignored +
 * stdout held OPEN). A process that **closes its stdout** — so the reader DRAINS and the flush join SUCCEEDS, no
 * SIGKILL — but **stays alive** (`sh -c 'exec 1>&-; sleep infinity'`) parks `waitFor()` forever. That unbounded
 * wait is what hung [com.tneff.cyppieagents.remote.BridgeLazyInitE2eTest] indefinitely (via `relay.close()`) and
 * threatened PO1's serial gate.
 *
 * The fix bounds the termination wait: on timeout escalate `destroy()`→`destroyForcibly()` (SIGKILL), wait once
 * more bounded, then abandon the wait loudly. This test drives exactly the uncovered case — a process whose
 * `destroy()` closes the pipe (reader drains, flush succeeds) but which only actually TERMINATES on
 * `destroyForcibly()`.
 *
 * Mutation: revert to a bare `process.awaitTerminated()` (no bound, no escalation) → `awaitTerminated` parks
 * forever (the process never terminates without SIGKILL) → `closeAndAwait` never returns → the `withTimeout`
 * reddens.
 */
class Cyp362TerminationTimeoutTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    /**
     * A process that **closes its stdout on SIGTERM** (so the reader drains → the flush join SUCCEEDS, no CYP-374
     * SIGKILL) but **stays alive** until SIGKILL. `awaitTerminated()` (= the real `Process.waitFor()`) only
     * returns once [destroyForcibly] has been called — modelling the closed-stdout-but-alive process whose
     * unbounded `waitFor()` is the CYP-362 hang.
     */
    private class ClosedStdoutButAliveProc : AgentProcess {
        private val lines = Channel<String>(Channel.UNLIMITED)
        private val terminated = CompletableDeferred<Unit>()
        @Volatile var forciblyKilled = false
            private set
        override val stdoutLines: Flow<String> = lines.receiveAsFlow()
        suspend fun feed(line: String) = lines.send(line)
        override suspend fun writeLine(line: String) {}
        override fun destroy() { lines.close() }                 // SIGTERM: stdout closes → reader drains, flush SUCCEEDS
        override fun destroyForcibly() { forciblyKilled = true; terminated.complete(Unit) } // SIGKILL: the process finally dies
        override suspend fun awaitTerminated() = terminated.await() // waitFor(): parks until SIGKILL — unbounded without the fix
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
        // path). THEN awaitTerminated() = waitFor() would park forever (the process is still alive). CYP-362
        // bounds it: the 200 ms termination timeout fires → escalate to destroyForcibly() (SIGKILL) → the process
        // terminates → the second bounded wait returns. The withTimeout proves closeAndAwait does not hang.
        withTimeout(5_000) { session.closeAndAwait() }

        assertTrue(proc.forciblyKilled, "CYP-362: an unbounded termination wait must escalate to SIGKILL, not park on waitFor() forever")
    }
}
