package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.connector.ClaudeCodeSession.StartupOutcome
import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.SystemEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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

    /**
     * CYP-380 (Team1's third eye, PROD-reachable): the durable-store write inside `onBind`
     * (JsonFileSessionStore.upsert → writeText / Files.move fallback) can throw IOException on a disk hiccup.
     * Un-contained it escapes into the reader's broad IOException catch and is MISREAD as a reader death → a
     * false DIED_UNBOUND + onProcessExit + CYP-360 heal → respawn of a LIVE, just-bound session (context loss on
     * a full disk). The fix wraps `onBind` in `runCatching` (same shape as `onTurnResult`): the bind stays BOUND
     * in-memory, the durable-resume entry is just missing.
     *
     * Asserts ALL FOUR (a green suite must not be able to pass a swallowed OR a death-signalled onBind), driven
     * at the `ClaudeCodeSession` seam where the wrap sits (a thrown ctor `onBind`, not the whole file store):
     *  (a) startupOutcome == BOUND (L120 runs after the wrapped onBind);
     *  (b) onProcessExit NOT fired;
     *  (c) no CYP-360 exit listener / no heal;
     *  (d) the reader LIVES ON — a SUBSEQUENT line is still processed (the real "not deaf" proof).
     * Mutation (drop the runCatching around onBind) MUST redden a/b/c/d: the IOException escapes → CYP-377 catch →
     * tail → DIED_UNBOUND, onProcessExit fires, listeners fire, and the reader ends before the 2nd line.
     */
    @Test
    fun onBindThrowsIOException_duringBind_staysBound_readerLivesOn() = runBlocking {
        val uncaught = AtomicReference<Throwable?>(null)
        val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, e -> uncaught.set(e) })
        val processExits = AtomicInteger(0)
        val exitListenerFired = AtomicInteger(0)
        val systemEventsSeen = java.util.concurrent.CopyOnWriteArrayList<String?>()
        val observer = object : SessionObserver {
            override fun onEvent(agentId: String, sessionId: String?, correlationId: String?, event: StreamJsonEvent) {
                if (event is SystemEvent) systemEventsSeen.add(event.sessionId)
            }
            override fun onTurnStart(agentId: String, sessionId: String?, correlationId: String) {}
            override fun onProcessExit(agentId: String, sessionId: String?) { processExits.incrementAndGet() }
            override fun onStopped(agentId: String) {}
        }
        val lines = Channel<String>(Channel.UNLIMITED)
        val proc = object : AgentProcess {
            override val stdoutLines: Flow<String> = lines.receiveAsFlow()
            override suspend fun writeLine(line: String) {}
            override fun destroy() { lines.close() }
        }
        val session = ClaudeCodeSession(
            "backend", proc, SessionTurnQueue(), scope, observer = observer,
            onBind = { throw IOException("disk full during durable session-store upsert") },
        )
        session.addExitListener { exitListenerFired.incrementAndGet() }
        session.start()

        // Bind: the first SystemEvent triggers onBind, which throws (the store write failed). The wrap contains it.
        lines.send("""{"type":"system","subtype":"init","session_id":"s-bind"}""")
        val outcome = withTimeoutOrNull(3_000) { session.awaitStartupOutcome() }
        // (d) reader-lives-on: a SUBSEQUENT line must still be processed — the session is not deaf.
        lines.send("""{"type":"system","subtype":"other","session_id":"s-after"}""")
        val sawSecond = withTimeoutOrNull(2_000) {
            while (systemEventsSeen.none { it == "s-after" }) delay(20)
            true
        } ?: false

        assertEquals(StartupOutcome.BOUND, outcome, "(a) the bind survives the store IOException — BOUND, not DIED_UNBOUND")
        assertEquals(0, processExits.get(), "(b) no false onProcessExit — a disk hiccup is not a process death")
        assertEquals(0, exitListenerFired.get(), "(c) no CYP-360 exit listener / no heal fires on a store hiccup")
        assertTrue(sawSecond, "(d) the reader LIVES ON — a subsequent line is still processed (the session is not deaf)")
        assertNull(uncaught.get(), "the store failure is contained + logged, not uncaught — got: ${uncaught.get()}")
        scope.cancel()
    }

    private fun recordingObserver(processExits: AtomicInteger) = object : SessionObserver {
        override fun onEvent(agentId: String, sessionId: String?, correlationId: String?, event: StreamJsonEvent) {}
        override fun onTurnStart(agentId: String, sessionId: String?, correlationId: String) {}
        override fun onProcessExit(agentId: String, sessionId: String?) { processExits.incrementAndGet() }
        override fun onStopped(agentId: String) {}
    }
}
