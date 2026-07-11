package com.tneff.cyppieagents

import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.model.AgentMessage
import com.tneff.cyppieagents.model.AssistantEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.TextBlock
import com.tneff.cyppieagents.model.UserTurn
import com.tneff.cyppieagents.routing.installAgentSocket
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CYP-382 — after an agent RESTART, the FIRST turn is ignored and only the second reaches the agent
 * ("always", both targets). Reproduced + localized by Tester2 (`qa/CYP-382-repro`) and verified here
 * against the real `AgentSocket` wiring: `AgentSocket` bound `val session = sessions().session(agentId)`
 * ONCE at WS connect and injected every frame through that captured ref. A restart swaps the registry
 * entry (`LifecycleManager.doSpawn`: `sessions.remove(old)` + `sessions.register(new)`) while the WS stays
 * open, so the captured ref pointed at the dead, removed session — the first turn after the restart was
 * lost (its `sendTurn` writes to a destroyed process, which THROWS on the closed writer; the throw at the
 * unguarded inject closes the WS → the client reconnects and binds the new session → the *second* turn
 * reaches it). The fix resolves the CURRENT session PER FRAME.
 *
 * These teeth drive the real `installAgentSocket` route over a real client WebSocket; only the session is a
 * fake (no live `claude`). To be faithful, each restart test first completes a PRE-restart turn round-trip
 * (proving the WS is fully established and the server has captured the old session in its frame loop) and
 * only THEN swaps the registry — exactly the field sequence (a stable open window, then a restart, then the
 * first turn). Sync is on a server-side signal ([FakeConnectorSession.gotTurn]) rather than the output pump,
 * because with no `agentEvents` wired the pump follows the captured session; in production the pump follows
 * the agentId via `agentEvents`, so ONLY the inject path held a stale ref (scoped fix is complete).
 */
class Cyp382WsSessionPerFrameTest {

    /** In-memory session: records injected turns, echoes an ack (so a pre-restart round-trip can sync), and
     *  signals each injected turn via a fresh [gotTurn] so a test can await a specific delivery. */
    private class FakeConnectorSession(override val agentId: String) : ConnectorSession {
        private val _events = MutableSharedFlow<StreamJsonEvent>(replay = 16, extraBufferCapacity = 64)
        override val events: Flow<StreamJsonEvent> = _events
        val receivedTurns = CopyOnWriteArrayList<UserTurn>()
        @Volatile var gotTurn = CompletableDeferred<UserTurn>()

        override suspend fun sendTurn(turn: UserTurn) {
            receivedTurns.add(turn)
            gotTurn.complete(turn)
            _events.emit(AssistantEvent(message = AgentMessage(content = listOf(TextBlock("ack:${turn.text}"))), sessionId = "fake"))
        }

        override fun close() {}
    }

    /** Send a UserTurn frame and read the ack echo back — a full round-trip that proves the frame reached the
     *  session and the WS is established (used to close the connect-vs-swap race before a restart). */
    private suspend fun io.ktor.websocket.WebSocketSession.turnRoundTrip(text: String) {
        send(Frame.Text(CommJson.encodeToString(UserTurn.serializer(), UserTurn(text))))
        val ack = assertIs<AssistantEvent>(
            CommJson.decodeFromString<StreamJsonEvent>((incoming.receive() as Frame.Text).readText()),
        )
        assertTrue(assertIs<TextBlock>(ack.message.content.single()).text.contains(text))
    }

    @Test
    fun firstTurnAfterEachRestartReachesTheCurrentSession_notTheDeadCapturedOne() = testApplication {
        // ROUNDS loop (CYP-368 style) for robust, deterministic reddening under the mutation: the connect-time
        // ref is captured ONCE and never updated, so under the mutation EVERY round's turn goes to a long-dead
        // session and the round-0 `fresh.gotTurn` await times out → RED at round 0, every run. Under the fix,
        // each round resolves the CURRENT session → all rounds deliver. Each round is fully sequenced by the
        // await (no cross-round overlap), so both outcomes are deterministic.
        val sessions = ConnectorSessions()
        var current = FakeConnectorSession("backend")
        sessions.register(current)
        application { installAgentSocket(sessions, authorize = { true }) }
        val client = createClient { install(ClientWebSockets) }

        client.webSocket("/ws/agent?agentId=backend") {
            // Warm-up round-trip: establishes the WS and captures `current` in the server's frame loop, closing
            // the connect-vs-swap race before the first restart.
            turnRoundTrip("warmup")

            repeat(12) { round ->
                val dead = current
                // RESTART, exactly as LifecycleManager.doSpawn: remove the old session + register the new one,
                // while THIS WS stays open (nothing closes /ws/agent on a restart → the connect-time ref goes stale).
                sessions.remove("backend")
                val fresh = FakeConnectorSession("backend")
                sessions.register(fresh)
                current = fresh

                // The FIRST turn after THIS restart, over the SAME still-open WS.
                val msg = "turn-$round"
                send(Frame.Text(CommJson.encodeToString(UserTurn.serializer(), UserTurn(msg))))

                // Fix: injected into the CURRENT session. Mutation (inject via the captured connect-time ref)
                // → the turn goes to the dead session, this await times out → RED.
                withTimeout(3_000) { fresh.gotTurn.await() }
                assertEquals(msg, fresh.receivedTurns.single().text, "round $round: the CURRENT session must receive the post-restart turn")
                assertTrue(dead.receivedTurns.none { it.text == msg }, "round $round: the dead, removed session must NOT receive it")
            }
        }
    }

    @Test
    fun frameDuringSwapWindow_whenSessionMomentarilyAbsent_isDiscardedNotCrashing() = testApplication {
        // The inject at :144 is NOT under CYP-368's per-agent transition lock, so a frame can land between
        // remove(old) and register(new) → session(agentId) == null. It must be discarded cleanly (no NPE, WS
        // stays open), and a later frame once the new session is registered must still reach it.
        val sessions = ConnectorSessions()
        val v1 = FakeConnectorSession("backend")
        sessions.register(v1)
        application { installAgentSocket(sessions, authorize = { true }) }
        val client = createClient { install(ClientWebSockets) }

        client.webSocket("/ws/agent?agentId=backend") {
            turnRoundTrip("vor-neustart")

            // Enter the swap window: old session gone, new NOT yet registered.
            sessions.remove("backend")
            // A frame in the window → `sessions().session(agentId)` is null → discarded (no NPE, no close).
            send(Frame.Text(CommJson.encodeToString(UserTurn.serializer(), UserTurn("im-swap-fenster"))))
            // Hold the window open long enough for the server to process that frame while the session is
            // genuinely absent (sub-ms inject vs a 200ms hold) — so the null path is deterministically taken.
            delay(200)

            // Finish the swap; a subsequent turn must reach the new session (the WS survived the null frame).
            val v2 = FakeConnectorSession("backend")
            sessions.register(v2)
            send(Frame.Text(CommJson.encodeToString(UserTurn.serializer(), UserTurn("nach-swap"))))

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
        application { installAgentSocket(sessions, authorize = { true }) }
        val client = createClient { install(ClientWebSockets) }

        client.webSocket("/ws/agent?agentId=backend") {
            send(Frame.Text(CommJson.encodeToString(UserTurn.serializer(), UserTurn("kalt-erste"))))
            withTimeout(3_000) { v.gotTurn.await() }
            assertEquals(listOf("kalt-erste"), v.receivedTurns.map { it.text })
        }
    }
}
