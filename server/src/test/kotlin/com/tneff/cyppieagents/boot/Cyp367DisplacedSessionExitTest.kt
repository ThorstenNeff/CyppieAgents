package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-367 — the **session-identity guard at the CORE** (`LifecycleManager.onObservedExit`). `onObservedExit` is
 * agentId-keyed and cannot otherwise tell WHICH session died (the open CYP-368 seam). A session that was
 * DISPLACED (`ConnectorSessions.register` replaces without closing, CYP-368) or REMOVED-without-join
 * (`ConnectorSessions.remove` cancels the reader but never joins it, CYP-351) keeps a live exit tail — and when
 * that OLD session finally dies, its exit must NOT move the run state of the NEW session that replaced it. The
 * break is **latent today** (no `server/main` caller of `remove`), but it is a landmine for the next caller, so
 * the guard lives at the core, not on the caller.
 *
 * Mutation: delete `if (sessions.session(agentId) !== session) return null` from `onObservedExit` → the displaced
 * OLD session's non-zero exit flips the NEW (RUNNING) session to ERROR → [displacedOldSession_exit_...] reds,
 * while [currentSession_exit_...] stays green (the non-vacuity partner: the guard must not block the CURRENT
 * session's own exit).
 */
class Cyp367DisplacedSessionExitTest {

    /** A session whose exit listener is STORED (fired by the test), so the straggling exit is timed precisely. */
    private class FakeSession(override val agentId: String) : ConnectorSession {
        var exit: ((Int?) -> Unit)? = null
        override val events: Flow<StreamJsonEvent> = emptyFlow()
        override suspend fun sendTurn(turn: UserTurn) {}
        override fun close() {}
        override fun addExitListener(listener: (exitCode: Int?) -> Unit) { exit = listener }
    }

    private fun managerOver(sessions: ConnectorSessions, vararg toSpawn: ConnectorSession): LifecycleManager {
        val queue = ArrayDeque(toSpawn.toList())
        return LifecycleManager(
            initialWorktrees = mapOf("backend" to "backend"),
            sessions = sessions,
            ensureWorktree = {},
            spawn = { _, _ -> queue.removeFirst() },
        ).also { it.bootAgent("backend") }
    }

    @Test
    fun displacedOldSession_exit_doesNotMoveTheNewSessionsRunState() {
        val sessions = ConnectorSessions()
        val old = FakeSession("backend")
        val new = FakeSession("backend")
        val manager = managerOver(sessions, old)
        // The OLD session is registered + RUNNING; its exit listener is wired (captures `old`).
        assertEquals(AgentRunState.RUNNING, manager.runStateOf("backend"))

        // Displace it through the CYP-368 front door: register(new) replaces `old` WITHOUT closing it — `old`'s
        // reader is still alive, still holding its exit listener. Run state is unchanged (register ≠ a transition).
        sessions.register(new)
        assertEquals(AgentRunState.RUNNING, manager.runStateOf("backend"))

        // `old`'s straggling reader finally dies with a crash code — the remove-not-joined / displaced tail.
        old.exit!!.invoke(137)

        assertEquals(
            AgentRunState.RUNNING, manager.runStateOf("backend"),
            "a displaced OLD session's exit must not move the NEW session's run state (CYP-367 identity guard)",
        )
    }

    @Test
    fun currentSession_exit_stillMovesRunState_guardIsNotOverbroad() {
        // Non-vacuity: the guard must NOT block the CURRENTLY-registered session's own exit — its crash still
        // flips to ERROR (the CYP-351 behaviour the guard exists to preserve, not defeat).
        val sessions = ConnectorSessions()
        val only = FakeSession("backend")
        val manager = managerOver(sessions, only)
        assertEquals(AgentRunState.RUNNING, manager.runStateOf("backend"))

        only.exit!!.invoke(137) // the CURRENT session crashes

        assertEquals(
            AgentRunState.ERROR, manager.runStateOf("backend"),
            "the currently-registered session's own crash still moves the run state (guard not over-broad)",
        )
    }
}
