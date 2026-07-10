package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.connector.ClaudeCodeSession.StartupOutcome
import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.StreamJsonEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-377 — the reader must tell **our own teardown** from a **real reader failure**, and the answer decides a
 * deploy. `AgentProcess.stdoutLines` reads via `readLine()` with no catch; our own `destroy()` closes the stream
 * under a parked read → `IOException: Stream closed`. The fix catches it in `ClaudeCodeSession`'s collect and
 * falls through to the tail, which the `closing` guard already discriminates.
 *
 * Green `boot ×10` proves only the **no-uncaught** half — a fix that silently swallows (`catch { return }`,
 * session deaf on a dead process) is ALSO 10/10 green and passes every pre-existing fake (they all `Channel`
 * close = clean EOF, none throws "Stream closed"). So the load-bearing tooth is the DIRECT one below: an
 * IOException on a `closing == false` reader must be REPORTED as a death — the full tail — not swallowed, not
 * re-raised.
 */
class Cyp377ReaderIoExceptionTest {

    /** `closing == true`: our own destroy() throws "Stream closed" → a clean EOF, guarded → no false death. */
    @Test
    fun ownDestroyStreamClosed_isCleanEof_noThrow_noFalseDeath() = runBlocking {
        val uncaught = AtomicReference<Throwable?>(null)
        val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, e -> uncaught.set(e) })
        val processExits = AtomicInteger(0)
        val observer = recordingObserver(processExits)
        val throwsOnDestroy = object : AgentProcess {
            private val lines = Channel<String>(Channel.UNLIMITED)
            override val stdoutLines: Flow<String> = lines.receiveAsFlow()
            override suspend fun writeLine(line: String) {}
            override fun destroy() { lines.close(IOException("Stream closed")) }
        }
        val session = ClaudeCodeSession("backend", throwsOnDestroy, SessionTurnQueue(), scope, observer = observer)
        session.start()

        withTimeoutOrNull(5_000) { session.closeAndAwait() }

        assertNull(uncaught.get(), "our own destroy() must not surface an uncaught IOException — it is a clean EOF")
        assertEquals(0, processExits.get(), "a stop we asked for is not a death — the closing guard holds")
        scope.cancel()
    }

    /**
     * Tooth #2 (mandatory, NOT replaceable by `boot ×10`): an IOException on a **live** (`closing == false`)
     * reader is REPORTED as a death via the **full tail**, and the coroutine completes **without** an uncaught.
     *
     * The fake THROWS `IOException("Stream closed")` (not a Channel close) on the `closing == false` path — the
     * raw-`destroy()` survivor that produced the boot-package leak. Asserts, in one test, all three failure modes
     * a green suite cannot tell apart:
     *  - swallow (`catch { return }`) ⇒ onProcessExit never fires ⇒ RED;
     *  - rethrow ⇒ `uncaught` is set ⇒ RED;
     *  - partial tail (onProcessExit but no exit listener / no DIED_UNBOUND) ⇒ BE-3/CYP-356 heal-incompatible ⇒ RED.
     * Only the full-tail death — indistinguishable from an EOF death — is green.
     */
    @Test
    fun liveReaderIoException_isReportedAsDeath_viaFullTail_noUncaught() = runBlocking {
        val uncaught = AtomicReference<Throwable?>(null)
        val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, e -> uncaught.set(e) })
        val processExits = AtomicInteger(0)
        val observer = recordingObserver(processExits)
        val exitListenerCode = CompletableDeferred<Int?>()
        /** A live reader that fails by THROWING (not Channel-close); no close()/closeAndAwait() first ⇒ closing==false. */
        val failsWhileLive = object : AgentProcess {
            override val stdoutLines: Flow<String> = flow {
                throw IOException("Stream closed") // unbidden, mid-life — the raw-destroy survivor's death
            }
            override suspend fun writeLine(line: String) {}
            override fun destroy() {}
        }
        val session = ClaudeCodeSession("backend", failsWhileLive, SessionTurnQueue(), scope, observer = observer)
        session.addExitListener { exitListenerCode.complete(it) } // CYP-360 exit listener (BE-3/CYP-356 heal seam)
        session.start()

        val outcome = withTimeoutOrNull(5_000) { session.awaitStartupOutcome() } // the full death tail runs
        withTimeoutOrNull(2_000) { exitListenerCode.await() }

        // (a) death REPORTED exactly once, not swallowed:
        assertEquals(1, processExits.get(), "a live-process reader failure must fire onProcessExit exactly once (not swallowed)")
        // (b) coroutine ended WITHOUT uncaught (gate stays green; not re-raised):
        assertNull(uncaught.get(), "the death must be a clean completion, not an uncaught rethrow — got: ${uncaught.get()}")
        // (c) FULL tail = BE-3/CYP-356 heal compat, indistinguishable from an EOF death:
        assertTrue(exitListenerCode.isCompleted, "the CYP-360 exit listeners must fire — the full death tail runs")
        assertEquals(StartupOutcome.DIED_UNBOUND, outcome, "the reader-death runs DIED_UNBOUND, same shape as an EOF death")
        scope.cancel()
    }

    private fun recordingObserver(processExits: AtomicInteger) = object : SessionObserver {
        override fun onEvent(agentId: String, sessionId: String?, correlationId: String?, event: StreamJsonEvent) {}
        override fun onTurnStart(agentId: String, sessionId: String?, correlationId: String) {}
        override fun onProcessExit(agentId: String, sessionId: String?) { processExits.incrementAndGet() }
        override fun onStopped(agentId: String) {}
    }
}
