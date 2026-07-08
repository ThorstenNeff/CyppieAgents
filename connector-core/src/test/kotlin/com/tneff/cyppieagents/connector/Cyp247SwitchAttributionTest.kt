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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-247 S3 — the load-bearing d-ii attribution tooth for the correct switch, exercised against the REAL
 * [ClaudeCodeSession.closeAndAwait]. The two `active()`-read axes of the shared mouth run in ONE reader-job
 * collect body: [SessionObserver.onEvent] (→ `onContextTokens → tokenUsage`) and [onTurnResult] (→
 * `hub.postAsAgent`). Both read "the active project" at the moment they fire. A switch A→B must, per S3:
 *  - **drain the outgoing session BEFORE `rescope`** (the r3 reorder), and
 *  - `closeAndAwait` must **`cancelAndJoin`** the reader (r4) so an in-flight `ResultEvent` body completes
 *    still under `active()==A` before the drain returns.
 *
 * We simulate the switch with a controllable [active] flag (the "rescope") and hold the reader body in-flight
 * with a non-suspending sleep (faithful: the real body has no suspension point between reading the line and
 * the active()-reads; `_events.emit`'s buffer=256 does not suspend). The load-bearing assertion: with
 * drain(`closeAndAwait`)-before-flip, BOTH axes attribute to **A**. **Mutation — revert `closeAndAwait` to
 * `readerJob?.cancel()` (no join): `closeAndAwait` returns before the body finishes → the flip to B lands
 * first → both axes read B → this reds.**
 */
class Cyp247SwitchAttributionTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    /** The "currently active project" both mouth axes read — flipped by the simulated switch's rescope. */
    @Volatile private var active = "A"
    @Volatile private var observerSaw: String? = null   // tokenUsage axis (the projector tap)
    @Volatile private var turnResultSaw: String? = null  // hub.postAsAgent axis (the mediation)
    private val bodyStarted = CompletableDeferred<Unit>()

    private class FakeProc : AgentProcess {
        private val lines = Channel<String>(Channel.UNLIMITED)
        override val stdoutLines: Flow<String> = lines.receiveAsFlow()
        suspend fun feed(line: String) = lines.send(line)
        override suspend fun writeLine(line: String) {}
        override fun destroy() { lines.close() }
    }

    private fun initLine(sid: String) = """{"type":"system","subtype":"init","session_id":"$sid"}"""
    private fun resultLine(sid: String) =
        """{"type":"result","subtype":"success","is_error":false,"session_id":"$sid","result":"ack"}"""

    private fun buildSession(proc: FakeProc): ClaudeCodeSession {
        val observer = object : SessionObserver {
            override fun onEvent(agentId: String, sessionId: String?, correlationId: String?, event: StreamJsonEvent) {
                if (event is ResultEvent) {
                    bodyStarted.complete(Unit)     // the reader body is now IN-FLIGHT (before the active()-reads)
                    Thread.sleep(300)              // hold it in-flight, non-suspending (like the real body)
                    observerSaw = active           // tokenUsage axis: which project is active when the tap fires
                }
            }
            override fun onTurnStart(agentId: String, sessionId: String?, correlationId: String) {}
            override fun onProcessExit(agentId: String, sessionId: String?) {}
            override fun onStopped(agentId: String) {}
        }
        return ClaudeCodeSession(
            agentId = "dev",
            process = proc,
            turnQueue = SessionTurnQueue(),
            scope = scope,
            observer = observer,
            onBind = {},
            onTurnResult = { turnResultSaw = active }, // post axis: fires AFTER observer, same reader body
        ).also { it.start() }
    }

    @Test
    fun drainBeforeRescope_withCancelAndJoin_inFlightTurnAttributesToOutgoing() = runBlocking {
        val proc = FakeProc()
        val session = buildSession(proc)
        proc.feed(initLine("s1"))    // bind (so the ResultEvent is mediated)
        proc.feed(resultLine("s1"))  // triggers the in-flight ResultEvent body (observer sleep)
        withTimeout(5_000) { bodyStarted.await() } // the body is now in-flight, under active()==A

        // The switch, done RIGHT: drain (closeAndAwait → cancelAndJoin waits for the body) THEN rescope.
        session.closeAndAwait()
        active = "B" // rescope — only AFTER the outgoing session is quiescent

        assertEquals("A", observerSaw, "tokenUsage axis: the in-flight turn is attributed to the OUTGOING project A")
        assertEquals("A", turnResultSaw, "postAsAgent axis: the in-flight turn is attributed to A (channel + stamp), never B")
    }

    @Test
    fun flipBeforeDrain_misAttributesToIncoming_provesTheReorderIsNecessary() = runBlocking {
        val proc = FakeProc()
        val session = buildSession(proc)
        proc.feed(initLine("s1"))
        proc.feed(resultLine("s1"))
        withTimeout(5_000) { bodyStarted.await() }

        // The switch WITHOUT the reorder: rescope BEFORE draining the outgoing → the in-flight body sees B.
        active = "B"
        session.closeAndAwait()

        assertEquals("B", observerSaw, "without drain-before-rescope, the in-flight turn mis-attributes to the incoming B")
        assertEquals("B", turnResultSaw, "…both axes — exactly the drop/mis-stamp the r3 reorder closes")
    }
}
