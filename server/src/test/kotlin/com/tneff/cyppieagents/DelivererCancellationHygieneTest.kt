package com.tneff.cyppieagents

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryDeliveryLog
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.mediation.MessageDeliverer
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-166 — deliverer log hygiene. `launchDrain` must distinguish a benign teardown **cancellation**
 * (scope.cancel / CYP-73 stop-restart cancels an in-flight drain) from a real drain failure: the
 * cancellation is rethrown (structured-concurrency) and NOT logged as ERROR; a real throwable is still
 * ERROR-surfaced. Otherwise the cancel noise (~1/6 on teardown) breeds alarm-fatigue AND masks the real
 * "ws closed mid-drain" failure (the CYP-141 RC2 path).
 */
class DelivererCancellationHygieneTest {

    private fun agents() = listOf(Agent("po", "PO", Role.PO, "po"), Agent("backend", "BE", Role.WORKER, "backend"))

    private class FakeSession(override val agentId: String, val onSend: suspend () -> Unit) : ConnectorSession {
        override val events: Flow<StreamJsonEvent> = emptyFlow()
        override suspend fun sendTurn(turn: UserTurn) = onSend()
        override fun close() {}
    }

    private fun captureDelivererErrors(): Pair<LogbackLogger, ListAppender<ILoggingEvent>> {
        val logger = LoggerFactory.getLogger("mediation.deliverer") as LogbackLogger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return logger to appender
    }

    /** Wire the real deliverer + a single backend [session], then post one task so the drain runs [session]. */
    private fun postToBackend(scope: CoroutineScope, session: ConnectorSession) {
        val store = InMemoryMessageStore()
        val hub = Hub(HubState.hubAndSpoke(agents(), HubState.OPERATOR_ID), store)
        val sessions = ConnectorSessions()
        val deliverer = MessageDeliverer({ hub.state }, { hub.state.activeProjectId }, { sessions }, store, InMemoryDeliveryLog(), scope)
        hub.onPosted = deliverer::onPosted
        sessions.addRegisterListener(deliverer::onSessionAttached)
        sessions.register(session)
        hub.postAsAgent("po", "po-backend", "task") // → onPosted → drain(backend) → session.sendTurn
    }

    @Test
    fun benignTeardownCancellation_isNotLoggedAsError() {
        val (logger, cap) = captureDelivererErrors()
        try {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val entered = CompletableDeferred<Unit>()
            // sendTurn suspends (drain is in-flight) until the scope is cancelled → CancellationException.
            postToBackend(scope, FakeSession("backend") { entered.complete(Unit); awaitCancellation() })
            runBlocking { withTimeout(5_000) { entered.await() } } // the drain is parked inside sendTurn
            scope.cancel() // teardown — cancels the in-flight drain
            runBlocking { delay(300) } // let the cancellation propagate through onFailure
            assertTrue(
                cap.list.none { it.level == Level.ERROR },
                "a benign teardown cancellation must NOT be logged as ERROR (got: ${cap.list.map { it.formattedMessage }})",
            )
        } finally {
            logger.detachAppender(cap)
        }
    }

    @Test
    fun aRealDrainThrowable_isStillLoggedAsError() {
        val (logger, cap) = captureDelivererErrors()
        try {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            postToBackend(scope, FakeSession("backend") { throw RuntimeException("ws closed mid-drain") })
            // a real failure must still surface — wait for the ERROR.
            runBlocking { withTimeout(5_000) { while (cap.list.none { it.level == Level.ERROR }) delay(20) } }
            assertTrue(cap.list.any { it.level == Level.ERROR }, "a real drain throwable must still be ERROR-surfaced")
            scope.cancel()
        } finally {
            logger.detachAppender(cap)
        }
    }
}
