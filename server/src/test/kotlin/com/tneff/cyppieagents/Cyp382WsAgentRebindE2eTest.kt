package com.tneff.cyppieagents

import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import com.tneff.cyppieagents.routing.agentSocket
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.websocket.Frame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CYP-382 E2E-Diskriminator — **die reale `/ws/agent`-Route über einen Neustart, mit dem realen Fehlerbild.**
 * Schließt die Scope-Grenze der Unit-Repro und bestätigt die von Backend2 am Objekt geteilte Lokalisierung.
 *
 * **Reine Server-Verdrahtung — KEIN `claude`-Spawn, KEIN Key, KEIN Prozess.** Gemountet wird die Produktions-
 * Route [agentSocket]; die Sessions sind Fakes in einer kontrollierten [ConnectorSessions]. Ein „Neustart" ist
 * exakt der Registry-Swap von `LifecycleManager.restart`: `remove(alt)` (⇒ `session.close()`, wie `destroy()`
 * den Writer schließt) + `register(neu)`.
 *
 * **Realer Mechanismus (PO/Backend2-Verfeinerung): Throw, nicht Swallow.** Nach dem Neustart ist die gehaltene
 * Session tot; `sendTurn` darauf **wirft** (der destroyte Prozess-Writer ist zu — `AgentProcess.kt:79`,
 * `IOException: Stream closed`). `AgentSocket.kt:144` ist ungeguarded → der Throw fliegt aus der Frame-Schleife
 * → `finally { pump.cancel() }` → **die WS schließt**. Im Browser triggert genau dieser Close den Reconnect
 * (→ neue WS → v2 → die zweite Nachricht reicht). Der rohe Testclient reconnected **nicht** → die neue Session
 * bekommt **nichts**.
 *
 * Der Test asserted das **reale Signal**: die WS schließt nach dem ersten Frame, und v2 bekommt nichts.
 */
class Cyp382WsAgentRebindE2eTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun turnFrame(text: String) = Frame.Text(json.encodeToString(UserTurn.serializer(), UserTurn(text)))

    /** Modelliert den realen destroyten Prozess: nach `close()` (= restart-remove) **wirft** `sendTurn`. */
    private class RealisticSession(override val agentId: String) : ConnectorSession {
        val received = CopyOnWriteArrayList<String>()
        @Volatile var closed = false
        override val events: Flow<StreamJsonEvent> = emptyFlow()
        override suspend fun sendTurn(turn: UserTurn) {
            if (closed) throw java.io.IOException("Stream closed") // AgentProcess:79 — Writer nach destroy() zu
            received.add(turn.text)
        }
        override fun close() { closed = true }
    }

    @Test
    fun afterRestart_firstMessageThrowsAndTearsDownWs_newSessionGetsNothing() = testApplication {
        val sessions = ConnectorSessions()
        val v1 = RealisticSession("backend").also { sessions.register(it) }

        application {
            install(ServerWebSockets) { contentConverter = KotlinxWebsocketSerializationConverter(json) }
            routing { agentSocket({ sessions }, authorize = { true }) } // Test opt-in der Zulassung (F-B-Default = deny)
        }

        val client = createClient { install(ClientWebSockets) }
        lateinit var v2: RealisticSession
        var wsClosedAfterFirstMessage = false

        client.webSocket("/ws/agent?agentId=backend") {
            // Die offene WS hat AgentSocket:107 `val session` = v1 gebunden.

            // NEUSTART (Operator drückt Restart, BEVOR er tippt): Registry-Swap. remove() schliesst v1 (destroy).
            sessions.remove("backend")
            v2 = RealisticSession("backend").also { sessions.register(it) }

            // ERSTE Nachricht NACH dem Neustart -> geht an die gehaltene, tote v1 -> sendTurn WIRFT -> WS-Teardown.
            send(turnFrame("erste-nach-neustart"))

            // Reales Signal: die WS schliesst (der Throw riss die Frame-Schleife ab). Kein Reconnect im Testclient.
            val reason = withTimeoutOrNull(3_000) { closeReason.await() }
            wsClosedAfterFirstMessage = reason != null
        }

        println("CYP382-E2E  v1(tot).received=${v1.received}  (die erste warf, wurde nicht aufgenommen)")
        println("CYP382-E2E  v2(neu).received=${v2.received}")
        println("CYP382-E2E  WS nach erster Nachricht geschlossen? $wsClosedAfterFirstMessage")
        println("CYP382-E2E  => ${if (wsClosedAfterFirstMessage && v2.received.isEmpty()) "REPRODUZIERT: Throw -> WS-Teardown, neue Session bekommt NICHTS" else "nicht wie erwartet"}")

        assertNotNull(v1, "Vorbedingung: die WS war an v1 gebunden")
        assertTrue(
            wsClosedAfterFirstMessage,
            "CYP-382 reales Signal: die erste Nachricht nach Neustart trifft die gehaltene tote Session, " +
                "sendTurn wirft (AgentProcess:79), AgentSocket:144 ist ungeguarded -> die WS wird abgerissen. " +
                "Im Browser triggert genau das den Reconnect (dann reicht die zweite).",
        )
        assertEquals(
            emptyList(), v2.received,
            "die NEUE Session bekommt nichts: die Socket klebte an der toten alten (kein Re-Bind). v2=${v2.received}",
        )
    }
}
