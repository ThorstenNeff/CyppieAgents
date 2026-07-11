package com.tneff.cyppieagents

import com.tneff.cyppieagents.agentevents.InMemoryAgentEventStore
import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import com.tneff.cyppieagents.routing.installAgentSocket
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-382 — after an agent RESTART, the FIRST turn is ignored and only the second reaches the agent
 * ("always", both targets). Reproduced + localized by Tester2 (`qa/CYP-382-repro`): `AgentSocket` bound
 * `val session = sessions().session(agentId)` ONCE at WS connect and injected every frame through that
 * captured ref. A restart swaps the registry entry (`LifecycleManager.doSpawn`: `sessions.remove(old)` +
 * `sessions.register(new)`) while the WS stays open, so the captured ref pointed at the dead, removed session
 * and the first post-restart turn was lost. The fix resolves the CURRENT session PER FRAME (and CYP-384 then
 * removed the connect-time capture entirely — no cached session ref survives at all).
 *
 * These teeth drive the real `installAgentSocket` route over a real client WebSocket; only the session is a
 * fake (no live `claude`). Sync is on a server-side signal ([FakeConnectorSession.gotTurn] / `receivedTurns`),
 * NOT the output pump — output is the durable agentId-keyed stream (CYP-384), independent of the inject path
 * under test. Each restart test first drives a PRE-restart turn (proving the WS is established and the server's
 * frame loop is running) and only THEN swaps the registry — the field sequence.
 */
class Cyp382WsSessionPerFrameTest {

    /** In-memory session: records injected turns and signals each via [gotTurn] so a test can await a delivery. */
    private class FakeConnectorSession(override val agentId: String) : ConnectorSession {
        private val _events = MutableSharedFlow<StreamJsonEvent>(replay = 16, extraBufferCapacity = 64)
        override val events: Flow<StreamJsonEvent> = _events
        val receivedTurns = CopyOnWriteArrayList<UserTurn>()
        @Volatile var gotTurn = CompletableDeferred<UserTurn>()
        override suspend fun sendTurn(turn: UserTurn) { receivedTurns.add(turn); gotTurn.complete(turn) }
        override fun close() {}
    }

    private suspend fun io.ktor.websocket.WebSocketSession.sendTurn(text: String) =
        send(Frame.Text(CommJson.encodeToString(UserTurn.serializer(), UserTurn(text))))

    @Test
    fun firstTurnAfterEachRestartReachesTheCurrentSession_notTheDeadCapturedOne() = testApplication {
        // ROUNDS loop (CYP-368 style) for robust, deterministic reddening under the mutation (a re-introduced
        // connect-time capture): that ref is taken ONCE, so under the mutation EVERY round's turn goes to a
        // long-dead session and the round-0 `fresh.gotTurn` await times out → RED at round 0, every run. Under
        // the fix, each round resolves the CURRENT session → all rounds deliver. Each round is fully sequenced
        // by the await (no cross-round overlap), so both outcomes are deterministic.
        val sessions = ConnectorSessions()
        var current = FakeConnectorSession("backend")
        sessions.register(current)
        application { installAgentSocket(sessions, authorize = { true }, agentEvents = InMemoryAgentEventStore()) }
        val client = createClient { install(ClientWebSockets) }

        client.webSocket("/ws/agent?agentId=backend") {
            // Warm-up: a pre-restart turn establishes the WS and gets the server's frame loop running, closing
            // the connect-vs-swap race before the first restart.
            val warmed = current
            sendTurn("warmup")
            withTimeout(3_000) { warmed.gotTurn.await() }

            repeat(12) { round ->
                val dead = current
                // RESTART, exactly as LifecycleManager.doSpawn: remove the old session + register the new one,
                // while THIS WS stays open (nothing closes /ws/agent on a restart → a connect-time ref would go stale).
                sessions.remove("backend")
                val fresh = FakeConnectorSession("backend")
                sessions.register(fresh)
                current = fresh

                // The FIRST turn after THIS restart, over the SAME still-open WS.
                val msg = "turn-$round"
                sendTurn(msg)

                // Fix: injected into the CURRENT session. Mutation (a re-introduced connect-time capture) → the
                // turn goes to the dead session, this await times out → RED.
                withTimeout(3_000) { fresh.gotTurn.await() }
                assertEquals(msg, fresh.receivedTurns.single().text, "round $round: the CURRENT session must receive the post-restart turn")
                assertTrue(dead.receivedTurns.none { it.text == msg }, "round $round: the dead, removed session must NOT receive it")
            }
        }
    }

    @Test
    fun frameDuringSwapWindow_whenSessionMomentarilyAbsent_isDiscardedNotCrashing() = testApplication {
        // The inject is NOT under CYP-368's per-agent transition lock, so a frame can land between remove(old)
        // and register(new) → session(agentId) == null. It must be discarded cleanly (no NPE, WS stays open),
        // and a later frame once the new session is registered must still reach it.
        val sessions = ConnectorSessions()
        val v1 = FakeConnectorSession("backend")
        sessions.register(v1)
        application { installAgentSocket(sessions, authorize = { true }, agentEvents = InMemoryAgentEventStore()) }
        val client = createClient { install(ClientWebSockets) }

        client.webSocket("/ws/agent?agentId=backend") {
            sendTurn("vor-neustart")
            withTimeout(3_000) { v1.gotTurn.await() }

            // Enter the swap window: old session gone, new NOT yet registered.
            sessions.remove("backend")
            // A frame in the window → `sessions().session(agentId)` is null → discarded (no NPE, no close).
            sendTurn("im-swap-fenster")
            // Hold the window open long enough for the server to process that frame while the session is
            // genuinely absent (sub-ms inject vs a 200ms hold) — so the null path is deterministically taken.
            delay(200)

            // Finish the swap; a subsequent turn must reach the new session (the WS survived the null frame).
            val v2 = FakeConnectorSession("backend")
            sessions.register(v2)
            sendTurn("nach-swap")

            withTimeout(3_000) { v2.gotTurn.await() }
            assertEquals(listOf("nach-swap"), v2.receivedTurns.map { it.text }, "the new session receives the post-window turn")
            assertTrue(v1.receivedTurns.none { it.text == "im-swap-fenster" }, "the discarded in-window frame reached no one (the old session is already gone)")
        }
    }

    /** Non-vacuity control: with NO restart, the first turn reaches the session (the route is not broken). */
    @Test
    fun coldStart_firstTurnReachesTheSession() = testApplication {
        val sessions = ConnectorSessions()
        val v = FakeConnectorSession("backend")
        sessions.register(v)
        application { installAgentSocket(sessions, authorize = { true }, agentEvents = InMemoryAgentEventStore()) }
        val client = createClient { install(ClientWebSockets) }

        client.webSocket("/ws/agent?agentId=backend") {
            sendTurn("kalt-erste")
            withTimeout(3_000) { v.gotTurn.await() }
            assertEquals(listOf("kalt-erste"), v.receivedTurns.map { it.text })
        }
    }
}
