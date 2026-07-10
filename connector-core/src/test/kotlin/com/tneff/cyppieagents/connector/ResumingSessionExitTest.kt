package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.mediation.SessionTurnQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-360 — a session's end must reach whoever asked to hear it, **through the resume facade**, which is what
 * [ClaudeCodeConnector.open] actually hands out whenever a durable session id exists.
 *
 * [ConnectorSession.addExitListener] has a no-op default. [ResumingSession] inheriting it means a subscriber
 * registers on a stub and never hears anything — while every test that builds a [ClaudeCodeSession] directly
 * stays green. That is not hypothetical: it is how this defect was found, in a fix whose own unit tests all
 * passed. **The test must go through the facade, or a green run lies.**
 *
 * It pins the opposite error too. The facade *replaces* its inner session when a `--resume` turns out to be
 * stale, and that discarded attempt is **meant** to end. Reporting it as the agent's end would mis-attribute a
 * successful heal as a death — an invented observation, manufactured by the fix itself.
 *
 * Mutation probes, both run:
 *  - delete `override fun addExitListener` from [ResumingSession] → [deathOfTheLiveSession_...] goes red;
 *  - delete the `session === firstAttempt && !session.everBound` guard → [deathOfAStaleResumeAttempt_...] goes
 *    red. The guard is **semantic** (did it ever bind?) rather than temporal (retire it when swapped), because
 *    the discarded attempt's end is what *triggers* the heal — retiring it on the swap always loses the race.
 */
class ResumingSessionExitTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest fun tearDown() = scope.cancel()

    /** A process whose stdout the test drives. */
    private class FakeProcess : AgentProcess {
        private val lines = Channel<String>(Channel.UNLIMITED)
        override val stdoutLines: Flow<String> = lines.receiveAsFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() { lines.close() }

        /** Bind the session, the way `claude` does with its `system/init`. */
        suspend fun bind(sid: String) = lines.send("""{"type":"system","subtype":"init","session_id":"$sid"}""")

        /** End stdout, as a dying process does. */
        fun end() = lines.close()
    }

    private fun session(process: AgentProcess) =
        ClaudeCodeSession(agentId = "backend", process = process, turnQueue = SessionTurnQueue(), scope = scope)

    @Test
    fun deathOfTheLiveSession_reachesAListenerRegisteredOnTheFacade() = runBlocking {
        val process = FakeProcess()
        val live = session(process)
        val facade = ResumingSession(
            agentId = "backend",
            scope = scope,
            firstAttempt = live,
            onResumeFailed = {},
            respawnFresh = { error("a bound resume must never be healed away") },
        )
        val heard = CompletableDeferred<Unit>()
        facade.addExitListener { heard.complete(Unit) } // the run-state authority subscribes exactly here
        facade.start()

        // A LIVE resume: the attempt binds. It IS the agent now, even though no turn has committed it yet
        // (CYP-170 commits on the first turn) — so its end is the agent's end.
        process.bind("sess-live")
        withTimeoutOrNull(2_000) { while (!live.everBound) delay(10) }

        process.end()

        assertEquals(Unit, withTimeoutOrNull(5_000) { heard.await() }, "the facade must forward its session's end")
    }

    /**
     * CYP-351 — a subscription that arrives **after** the session already ended must be handed the end, not
     * silence. The connector starts the process before returning the session, so the run-state authority always
     * subscribes late; an agent that dies inside that handover would otherwise die into an empty listener list.
     * The observation would not be delayed, it would never exist.
     *
     * Mutation: drop the sticky replay in `addExitListener` (both here and in `ClaudeCodeSession`) → red.
     */
    @Test
    fun aSubscriptionAfterTheEnd_replaysIt_insteadOfHearingSilence() = runBlocking {
        val process = FakeProcess()
        val live = session(process)
        val facade = ResumingSession(
            agentId = "backend", scope = scope, firstAttempt = live,
            onResumeFailed = {}, respawnFresh = { error("not reached") },
        )
        // An early probe on the inner session tells us when the end has been published, without peeking at
        // private state and without a sleep.
        val published = CompletableDeferred<Unit>()
        live.addExitListener { published.complete(Unit) }

        facade.start()
        process.bind("sess-live")
        withTimeoutOrNull(2_000) { while (!live.everBound) delay(10) }

        process.end()
        assertEquals(Unit, withTimeoutOrNull(5_000) { published.await() }, "the session must have ended first")

        val heard = CompletableDeferred<Unit>()
        facade.addExitListener { heard.complete(Unit) } // the late subscriber

        assertEquals(Unit, withTimeoutOrNull(2_000) { heard.await() }, "a late subscriber must be told the end")
    }

    @Test
    fun deathOfAStaleResumeAttempt_isNotReported_becauseTheAgentDidNotDie() = runBlocking {
        // The first attempt ends unbound (no system/init) → the CYP-330 probe heals to a fresh session. The
        // discarded attempt's end is a step of the heal, not the end of the agent.
        val stale = FakeProcess()
        val freshProcess = FakeProcess()
        val freshBuilt = CompletableDeferred<Unit>()
        val facade = ResumingSession(
            agentId = "backend",
            scope = scope,
            firstAttempt = session(stale),
            onResumeFailed = {},
            respawnFresh = { session(freshProcess).also { freshBuilt.complete(Unit) } },
        )
        val heard = CompletableDeferred<Unit>()
        facade.addExitListener { heard.complete(Unit) }
        facade.start()

        stale.end() // → DIED_UNBOUND → heal

        assertNull(
            withTimeoutOrNull(1_500) { heard.await() },
            "a replaced resume attempt must not be reported as the agent's end",
        )
        assertEquals(Unit, withTimeoutOrNull(2_000) { freshBuilt.await() }, "the heal must actually have run")

        // ...and the fresh session, which now IS the agent, is the one whose end counts.
        freshProcess.end()
        assertEquals(Unit, withTimeoutOrNull(5_000) { heard.await() }, "the live session's end must be reported")
    }
}
