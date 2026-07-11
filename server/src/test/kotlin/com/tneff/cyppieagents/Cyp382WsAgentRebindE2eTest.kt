package com.tneff.cyppieagents

import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.model.UserTurn
import com.tneff.cyppieagents.routing.agentSocket
import com.tneff.cyppieagents.support.RecordingSession
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.websocket.Frame
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-382 E2E-Diskriminator — **die reale `/ws/agent`-Route über einen Neustart.** Schließt die Scope-Grenze
 * der Unit-Repro ([Cyp382FirstMessageAfterRestartReproTest]): zeigt definitiv, ob ein Neustart die offene
 * Socket an die **tote** Session gebunden lässt (meine Lesung) oder ob sie **re-bindet** (Resume-Lesung).
 *
 * **Reine Server-Verdrahtung — KEIN echter `claude`-Spawn, KEIN API-Key, KEIN Prozess.** Gemountet wird die
 * Produktions-Route [agentSocket]; die Sessions sind [RecordingSession]s in einer von mir kontrollierten
 * [ConnectorSessions]. Ein „Neustart" ist exakt, was `LifecycleManager.restart` an der Registry tut:
 * `remove(alt)` + `register(neu)`.
 *
 * **Kein Reconnect-Confounder:** ein roher WS-Testclient verbindet nicht neu wie Browser/Desktop. Beide Frames
 * gehen über **dieselbe offene** WS. Klebt `AgentSocket.kt:107` (`val session = sessions().session(agentId)`,
 * einmal beim Connect gebunden) an der toten Session, bekommt die **neue** Session **gar nichts** — auch nicht
 * den zweiten Frame. Das trennt „Socket klebt" von „Socket re-bindet" ohne Störfaktor.
 */
class Cyp382WsAgentRebindE2eTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun turnFrame(text: String) = Frame.Text(json.encodeToString(UserTurn.serializer(), UserTurn(text)))

    @Test
    fun openWs_restartSwapsSession_doesTheSocketRebindOrStickToTheDeadSession() = testApplication {
        val sessions = ConnectorSessions()
        val v1 = RecordingSession("backend").also { sessions.register(it) }

        application {
            install(ServerWebSockets) { contentConverter = KotlinxWebsocketSerializationConverter(json) }
            routing {
                // Produktions-Route. authorize={true}: Tests opten die Zulassung explizit ein (F-B-Default = deny).
                agentSocket({ sessions }, authorize = { true })
            }
        }

        val client = createClient { install(ClientWebSockets) }
        lateinit var v2: RecordingSession

        client.webSocket("/ws/agent?agentId=backend") {
            // Die offene WS hat jetzt AgentSocket:107 `val session` = v1 gebunden.
            send(turnFrame("erste-nach-neustart"))
            delay(150) // Server verarbeitet den Frame ueber die gehaltene Referenz

            // NEUSTART: exakt der Registry-Swap von LifecycleManager.restart (remove alt + register neu).
            sessions.remove("backend")
            v2 = RecordingSession("backend").also { sessions.register(it) }

            // Zweiter Frame ueber DIESELBE offene WS.
            send(turnFrame("zweite-nach-neustart"))
            delay(150)
        }

        // ---- rohe Beobachtung, als Evidenz auslesbar
        println("CYP382-E2E  v1(tot).received=${v1.received}")
        println("CYP382-E2E  v2(neu).received=${v2.received}")
        val v2GotAnything = v2.received.isNotEmpty()
        println("CYP382-E2E  => ${if (!v2GotAnything) "SOCKET KLEBT an toter Session (v2 bekommt NICHTS)" else "Socket re-bindet (v2 bekommt etwas)"}")

        // Der Diskriminator, als harte Assertion: der Neustart lässt die Socket an der toten Session.
        assertTrue(
            v1.received.any { it.contains("erste") },
            "Vorbedingung: die erste Nachricht erreichte v1 vor dem Neustart (die WS war gebunden)",
        )
        assertEquals(
            emptyList(), v2.received,
            "CYP-382: nach dem Neustart bekommt die NEUE Session nichts — die offene /ws/agent-WS klebt an der " +
                "toten alten Session (AgentSocket:107 bindet `val session` einmal beim Connect, re-bindet nie). " +
                "v2.received=${v2.received}",
        )
    }
}
