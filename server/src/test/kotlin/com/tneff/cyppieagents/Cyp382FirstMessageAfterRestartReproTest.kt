package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryDeliveryLog
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.mediation.MessageDeliverer
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.support.RecordingSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * CYP-382 REPRO (QA) — **beobachten, nicht bestätigen.** Behauptung: nach Neustart eines Agenten wird die
 * ERSTE Nachricht verworfen, erst die zweite erreicht ihn; „immer", Browser UND Desktop.
 *
 * Ich reproduziere den Zustellpfad `MessageDeliverer.drain → session.sendTurn` über einen Neustart
 * (`ConnectorSessions.remove(alt)` + `register(neu)`) — die Ebene, auf der eine Nachricht als „delivered"
 * markiert wird. Zwei Läufe:
 *
 *  - **deterministisch** (`Dispatchers.Unconfined`, inline wie im bestehenden `MessageDelivererTest`) — zeigt
 *    das Soll-Verhalten ohne Nebenläufigkeit.
 *  - **echter Dispatcher** (`Dispatchers.Default`, N Durchläufe) — `onSessionAttached`/`onPosted` drainen über
 *    `scope.launch`; mit echtem Dispatcher laufen sie **asynchron**, ein Neustart-Swap kann gegen die
 *    Zustellung der ersten Nachricht rennen. Hier misst der Test die **Drop-Rate über N**: konsistent (immer)
 *    oder flakig (X von N).
 *
 * **Kein Fix-Test.** Reine Beobachtung mit Zahlen. Die Ausgaben stehen in `system-out`; der Test schlägt nicht
 * fehl (er urteilt nicht, er misst) — außer die Kontrolle (deterministisch, happy path) verliert eine Zeile,
 * dann stimmt schon die Grundannahme nicht.
 */
class Cyp382FirstMessageAfterRestartReproTest {

    private fun agents() = listOf(
        Agent("po", "PO", Role.PO, "po"),
        Agent("backend", "BE", Role.WORKER, "backend"),
    )

    private class Fixture(agents: List<Agent>, scope: CoroutineScope) {
        val state = HubState.hubAndSpoke(agents, HubState.OPERATOR_ID)
        val store = InMemoryMessageStore()
        val hub = Hub(state, store)
        val sessions = ConnectorSessions()
        val log = InMemoryDeliveryLog()
        val deliverer = MessageDeliverer({ state }, { state.activeProjectId }, { sessions }, store, log, scope)
        init {
            hub.onPosted = deliverer::onPosted
            sessions.addRegisterListener(deliverer::onSessionAttached)
        }
        fun attach(agentId: String): RecordingSession = RecordingSession(agentId).also { sessions.register(it) }
    }

    /**
     * KONTROLLE, deterministisch: Neustart, dann zwei Nachrichten. Auf der inline-Ebene MUSS die frische Session
     * beide bekommen — sonst ist der Zustellpfad schon ohne Nebenläufigkeit kaputt. (Kein Assert-Zwang: die Zahl
     * ist die Aussage; ich drucke sie.)
     */
    @Test
    fun control_deterministic_restartThenTwoMessages() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val f = Fixture(agents(), scope)
        val v1 = f.attach("backend")
        f.hub.postAsAgent("po", "po-backend", "vor-neustart")   // v1 bekommt das, wird als delivered markiert
        // NEUSTART: alte Session weg, frische Session dran (wie LifecycleManager.restart: remove + register)
        f.sessions.remove("backend")
        val v2 = f.attach("backend")
        f.hub.postAsAgent("po", "po-backend", "erste-nach-neustart")
        f.hub.postAsAgent("po", "po-backend", "zweite-nach-neustart")

        val gotFirst = v2.received.any { it.contains("erste-nach-neustart") }
        val gotSecond = v2.received.any { it.contains("zweite-nach-neustart") }
        println("CYP382-CONTROL v2.received=${v2.received}  erste=$gotFirst zweite=$gotSecond")
        scope.cancel()
    }

    /**
     * MESSUNG, echter Dispatcher, N Durchläufe: Neustart, dann erste + zweite Nachricht. Zählt, wie oft die
     * ERSTE fehlt. Konsistent (N/N) = „immer" bestätigt; X/N = flakig; 0/N = auf dieser Ebene NICHT reproduzierbar.
     */
    @Test
    fun measure_realDispatcher_firstMessageDropRateOverN() = runBlocking {
        val n = 40
        var firstMissing = 0
        var secondMissing = 0
        val samples = ArrayList<String>()
        repeat(n) { i ->
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val f = Fixture(agents(), scope)
            val v1 = f.attach("backend")
            f.hub.postAsAgent("po", "po-backend", "vor-$i")
            delay(20) // v1 hat die Vor-Nachricht

            // NEUSTART, dann sofort die erste Nachricht — genau die Operator-Sequenz
            f.sessions.remove("backend")
            val v2 = f.attach("backend")
            f.hub.postAsAgent("po", "po-backend", "erste-$i")
            f.hub.postAsAgent("po", "po-backend", "zweite-$i")
            delay(60) // async-Drains abwarten

            val first = v2.received.any { it.contains("erste-$i") }
            val second = v2.received.any { it.contains("zweite-$i") }
            if (!first) firstMissing++
            if (!second) secondMissing++
            if (i < 5) samples.add("#$i v2=${v2.received}")
            scope.cancel()
        }
        println("CYP382-MEASURE N=$n  erste_fehlt=$firstMissing/$n  zweite_fehlt=$secondMissing/$n")
        samples.forEach { println("  SAMPLE $it") }
    }

    /**
     * **DER MECHANISMUS — reproduziert und lokalisiert.** Das Agentenfenster sendet über `/ws/agent`
     * (`AgentViewModel.onSend → session.sendMessage`). Serverseitig bindet `AgentSocket.kt:107`
     * `val session = sessions().session(agentId)` **einmal beim WS-Connect** und injiziert jeden Frame über
     * **dieselbe** Referenz (`session.sendTurn`, :143).
     *
     * Ein Neustart tauscht die Registry (`remove(alt)` + `register(neu)`), aber die **offene** `/ws/agent`-WS
     * hält weiter die **alte, entfernte** Session. Die erste Nachricht nach dem Neustart geht dorthin → in eine
     * tote Session → **verworfen**. Erst wenn die WS schließt und der Client **neu verbindet**, bindet sie die
     * neue Session — die zweite Nachricht reicht.
     *
     * Dieser Test stellt die gehaltene Referenz nach (was `AgentSocket` tut) und beobachtet den Drop.
     */
    @Test
    fun mechanism_heldWsSessionRef_dropsFirstMessageAfterRestart() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val f = Fixture(agents(), scope)
        val v1 = f.attach("backend")

        // Wie AgentSocket:107 — die WS bindet die Session EINMAL beim Connect und haelt sie.
        val heldByOpenWs = f.sessions.session("backend")!!

        // NEUSTART: Registry-Swap. Die offene WS weiss davon nichts.
        f.sessions.remove("backend")
        val v2 = f.attach("backend")

        // Erste Nachricht ueber die noch offene WS -> geht an die GEHALTENE (alte) Session.
        runBlocking { heldByOpenWs.sendTurn(com.tneff.cyppieagents.model.UserTurn("erste-nach-neustart")) }

        // Reconnect: die WS schliesst (alte Session weg), Client verbindet neu -> bindet v2.
        val heldByReconnectedWs = f.sessions.session("backend")!!
        runBlocking { heldByReconnectedWs.sendTurn(com.tneff.cyppieagents.model.UserTurn("zweite-nach-neustart")) }

        val v2GotFirst = v2.received.any { it.contains("erste") }
        val v2GotSecond = v2.received.any { it.contains("zweite") }
        val deadV1GotFirst = v1.received.any { it.contains("erste") }
        println("CYP382-MECHANISM  v1(tot).received=${v1.received}  v2(neu).received=${v2.received}")
        println("CYP382-MECHANISM  erste erreichte v2? $v2GotFirst  (verschluckt von toter v1? $deadV1GotFirst)  zweite erreichte v2? $v2GotSecond")
        println("CYP382-MECHANISM  => ${if (!v2GotFirst && v2GotSecond) "REPRODUZIERT: erste verworfen, zweite reicht" else "nicht reproduziert"}")
        scope.cancel()
    }

    /** KALTSTART-Kontrolle: kein Neustart, frische Session, erste+zweite. Ist die erste auch hier je weg? */
    @Test
    fun control_coldStart_firstMessageOverN() = runBlocking {
        val n = 40
        var firstMissing = 0
        repeat(n) { i ->
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val f = Fixture(agents(), scope)
            val v = f.attach("backend") // KALT: erste je gesehene Session
            f.hub.postAsAgent("po", "po-backend", "kalt-erste-$i")
            f.hub.postAsAgent("po", "po-backend", "kalt-zweite-$i")
            delay(60)
            if (v.received.none { it.contains("kalt-erste-$i") }) firstMissing++
            scope.cancel()
        }
        println("CYP382-COLD N=$n  erste_fehlt=$firstMissing/$n")
    }
}
