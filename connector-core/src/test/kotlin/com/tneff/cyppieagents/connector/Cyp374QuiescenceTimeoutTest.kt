package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.ResultEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
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
import kotlin.test.assertEquals

/**
 * CYP-374 — the quiescence-timeout hole in [ClaudeCodeSession.closeAndAwait]. Normally `destroy()` (SIGTERM)
 * closes the pipe, the reader drains, and the `join()` reaches quiescence so CYP-247's switch-attribution barrier
 * holds (an in-flight `ResultEvent` body completes under `active()==A` before the drain returns —
 * [Cyp247SwitchAttributionTest]). But a process that **ignores SIGTERM** and holds its stdout open past the flush
 * timeout would make the OLD code fall to a plain `readerJob?.cancel()` — the EXACT `cancel()`-without-join the
 * CYP-247 mutation forbids: the severed in-flight body then completes AFTER the return, concurrent with the
 * switch's rescope, and mis-attributes to the incoming project.
 *
 * The fix escalates SIGTERM→SIGKILL: `destroyForcibly()` closes the pipe UNCONDITIONALLY, so the reader CAN
 * drain, and a bounded second `join()` reaches true quiescence. This test drives exactly that — a process whose
 * `destroy()` is a no-op but whose `destroyForcibly()` closes the pipe — with an in-flight body held across the
 * (short, injected) flush timeout.
 *
 * Mutation: revert the timeout path to a plain `readerJob?.cancel()` (no SIGKILL escalation) → closeAndAwait
 * returns before the severed body finishes → the flip to B lands first → the body reads B → this reds.
 */
class Cyp374QuiescenceTimeoutTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    @Volatile private var active = "A"
    @Volatile private var observerSaw: String? = null   // tokenUsage axis (the projector tap)
    @Volatile private var turnResultSaw: String? = null  // hub.postAsAgent axis (the mediation)
    private val bodyStarted = CompletableDeferred<Unit>()

    /**
     * A process that **ignores SIGTERM** ([destroy] is a no-op — the pipe stays OPEN, the reader can't drain)
     * but dies on **SIGKILL** ([destroyForcibly] closes the pipe → the reader drains). Exactly the case
     * closeAndAwait's escalation exists for.
     */
    private class SigtermIgnoringProc : AgentProcess {
        private val lines = Channel<String>(Channel.UNLIMITED)
        override val stdoutLines: Flow<String> = lines.receiveAsFlow()
        suspend fun feed(line: String) = lines.send(line)
        override suspend fun writeLine(line: String) {}
        override fun destroy() { /* SIGTERM ignored: pipe stays open, the reader parks — no drain */ }
        override fun destroyForcibly() { lines.close() } // SIGKILL: pipe closes → the reader drains
    }

    private fun initLine(sid: String) = """{"type":"system","subtype":"init","session_id":"$sid"}"""
    private fun resultLine(sid: String) =
        """{"type":"result","subtype":"success","is_error":false,"session_id":"$sid","result":"ack"}"""

    private fun buildSession(proc: SigtermIgnoringProc): ClaudeCodeSession {
        val observer = object : SessionObserver {
            override fun onEvent(agentId: String, sessionId: String?, correlationId: String?, event: StreamJsonEvent) {
                if (event is ResultEvent) {
                    bodyStarted.complete(Unit)  // the reader body is now IN-FLIGHT, before the active()-reads
                    Thread.sleep(450)           // hold it in-flight ACROSS the 300 ms flush timeout (non-suspending)
                    observerSaw = active        // tokenUsage axis: which project is active when the body finishes
                }
            }
            override fun onTurnStart(agentId: String, sessionId: String?, correlationId: String) {}
            override fun onProcessExit(agentId: String, sessionId: String?, exitCode: Int?) {}
            override fun onStopped(agentId: String) {}
        }
        return ClaudeCodeSession(
            agentId = "dev",
            process = proc,
            turnQueue = SessionTurnQueue(),
            scope = scope,
            observer = observer,
            onBind = {},
            onTurnResult = { turnResultSaw = active }, // post axis: fires after observer, same reader body
            readerFlushTimeoutMs = 300L, // < the 450 ms in-flight body → the FIRST join times out mid-body → escalate
        ).also { it.start() }
    }

    @Test
    fun sigtermIgnored_closeAndAwait_escalatesToSigkill_andHoldsTheQuiescenceBarrier() = runBlocking {
        val proc = SigtermIgnoringProc()
        val session = buildSession(proc)
        proc.feed(initLine("s1"))    // bind, so the ResultEvent is mediated
        proc.feed(resultLine("s1"))  // triggers the in-flight ResultEvent body
        withTimeout(5_000) { bodyStarted.await() } // the body is in-flight, under active()==A

        // closeAndAwait must reach quiescence even though the process ignores SIGTERM: destroy() does nothing →
        // the 300 ms flush timeout fires mid-body → CYP-374 escalates to destroyForcibly() (SIGKILL) → the pipe
        // closes → the reader drains → the bounded second join waits for the in-flight body to FINISH → only then
        // return. THEN rescope. The `withTimeout` proves it does not hang.
        withTimeout(5_000) { session.closeAndAwait() }
        active = "B" // rescope — only AFTER the outgoing session is quiescent

        assertEquals("A", observerSaw, "tokenUsage axis: the in-flight turn attributes to the OUTGOING project A (SIGKILL escalation joined it to quiescence)")
        assertEquals("A", turnResultSaw, "postAsAgent axis: the in-flight turn attributes to A, never the incoming B")
    }
}
