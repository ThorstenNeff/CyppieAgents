package com.tneff.cyppieagents.report

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.events.EventDraft
import com.tneff.cyppieagents.events.EventRecorder
import com.tneff.cyppieagents.events.InMemoryEventSink
import com.tneff.cyppieagents.events.LatchableEventSink
import com.tneff.cyppieagents.events.ManualTimeSource
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.ReportType
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CYP-353 (QA) — **Der Report zählt Meldungen, nicht verworfene Ereignisse.**
 *
 * `ReportGenerator.kt:103`: `events.count { it.type == LOG_DROPPED }`. Eine `log.dropped`-Meldung trägt aber
 * ein **Delta** (`detail.dropped`) und einen **kumulativen Stand** (`detail.total`, seit Serverstart).
 * Zwei Meldungen über 3 und 5 verworfene Ereignisse sind **8 verlorene Ereignisse**, nicht „2".
 *
 * ## Der Trennversuch
 *
 * Vier plausible Implementierungen liefern in Fall 1 **vier verschiedene Zahlen**. Ein Testfall, der sie nicht
 * trennt, beweist nichts — er sagt nur „nicht 8", nicht **welcher** Fehler vorliegt:
 *
 * | Implementierung | Fall 1 |
 * |---|---|
 * | `count { LOG_DROPPED }` (heute) | **2** |
 * | `sum { detail.total }` | **31** |
 * | `last.detail.total` | **18** |
 * | `last.detail.dropped` | **5** |
 * | **richtig:** `sum { detail.dropped }` | **8** |
 *
 * Deshalb prüft der Test die **Zahl im Text**, nicht ihre Anwesenheit. `text.contains("8")` wäre auch für
 * **18** wahr — einen der vier falschen Werte. Ein Beleg, der die Frage nicht stellen kann, die er zu
 * beantworten vorgibt, ist kein Beleg.
 *
 * ## Warum die Fixtures `projectId = "default"` benutzen
 *
 * Der echte `EventRecorder` stempelt `log.dropped` mit `projectId = PLATFORM` (`"platform"`), der Report
 * filtert auf `state.activeProjectId` (`"default"`). **Das ist ein zweiter, schwererer Defekt** — siehe
 * [Cyp353TelemetryGapNeverReachesTheReportTest]. Die Fälle 1 und 2 setzen ihn bewusst außer Kraft, damit ein
 * roter Test **eine** Ursache benennt. Zwei Defekte in einem Testfall ergeben einen Test, der nach dem halben
 * Fix immer noch rot ist und niemandem sagt, warum.
 */
class Cyp353DroppedIsASumNotACountTest {

    private class Fix {
        val time = ManualTimeSource(start = 1_000L)
        val sink = InMemoryEventSink(time)
        val state = HubState.hubAndSpoke(
            listOf(Agent("po", "PO", Role.PO, "po"), Agent("backend", "BE", Role.WORKER, "backend")),
            HubState.OPERATOR_ID, "default",
        )
        val gen = ReportGenerator(sink, state, Hub(state, InMemoryMessageStore()))
    }

    /** Eine `log.dropped`-Meldung, wie der echte [EventRecorder] sie schreibt — Delta **und** kumulativer Stand. */
    private fun dropReport(delta: Int, total: Int) = EventDraft(
        agentId = "platform", projectId = "default", type = EventType.LOG_DROPPED, severity = Severity.WARN,
        detail = buildJsonObject { put("dropped", delta); put("total", total) },
    )

    /** Die Zahl, die der Operator liest. Geparst, nicht gesucht: `contains("8")` ist auch für 18 wahr. */
    private fun gapCount(text: String): Int? = Regex("""\d+""").find(text)?.value?.toInt()

    private suspend fun gapItemText(f: Fix, since: Long?): String? =
        f.gen.build(ReportType.DEFECTS, since, null).sections.first { it.key == "defects" }
            .items.firstOrNull { it.refLabel == "log.dropped" }?.text

    // ---------------------------------------------------------------- Fall 1

    @Test
    fun twoReportsOverThreeAndFive_areEightLostEvents_notTwoMessages() = runBlocking {
        val f = Fix()

        // Vorlast VOR dem Fenster: 10 bereits verworfene Ereignisse. Deshalb starten die Totals bei 13, nicht bei 3.
        f.time.clock = 1_000L
        f.sink.appendBatch(listOf(dropReport(delta = 10, total = 10)))

        f.time.clock = 2_000L
        f.sink.appendBatch(listOf(dropReport(delta = 3, total = 13)))
        f.time.clock = 2_500L
        f.sink.appendBatch(listOf(dropReport(delta = 5, total = 18)))

        val text = gapItemText(f, since = 1_500L)
        assertNotNull(text, "Vorbedingung: die Telemetrie-Lücke muss überhaupt gemeldet werden")

        val n = gapCount(text)
        assertEquals(
            8, n,
            "Der Report muss die Summe der Deltas im Fenster nennen (3 + 5 = 8). " +
                "Gelesen: $n. 2 = Anzahl der Meldungen (heutiger Fehler) · 31 = Summe der Totals · " +
                "18 = letzter Total · 5 = letztes Delta. Jede Zahl benennt eine andere falsche Implementierung.",
        )
    }

    @Test
    fun theWindowIsHonored_preloadOutsideItIsNotCounted() = runBlocking {
        val f = Fix()
        f.time.clock = 1_000L
        f.sink.appendBatch(listOf(dropReport(delta = 10, total = 10)))
        f.time.clock = 2_000L
        f.sink.appendBatch(listOf(dropReport(delta = 3, total = 13)))

        val text = assertNotNull(gapItemText(f, since = 1_500L), "Vorbedingung: Lücke wird gemeldet")
        assertEquals(3, gapCount(text), "Die Vorlast (10) liegt vor dem Fenster und darf nicht mitgezählt werden")
    }

    // ---------------------------------------------------------------- Fall 2 (Pflicht)

    /**
     * **`total` ist nicht monoton.** Es zählt seit *Serverstart*. Nach einem Neustart beginnt es wieder klein.
     * Wer `letzter Total − erster Total` rechnet, bekommt hier `5 − 13 = −8`: eine negative Zahl verworfener
     * Ereignisse. Wer die Totals summiert, bekommt 18. Nur die Summe der **Deltas** überlebt den Neustart.
     */
    @Test
    fun serverRestartResetsTotal_theSumOfDeltasSurvivesIt() = runBlocking {
        val f = Fix()
        f.time.clock = 3_000L
        f.sink.appendBatch(listOf(dropReport(delta = 3, total = 13)))

        // --- Serverneustart: der kumulative Zähler fängt von vorn an, die Uhr läuft weiter.
        f.time.clock = 3_500L
        f.sink.appendBatch(listOf(dropReport(delta = 5, total = 5)))

        val text = assertNotNull(gapItemText(f, since = 2_900L), "Vorbedingung: Lücke wird gemeldet")
        val n = gapCount(text)
        assertEquals(
            8, n,
            "Über einen Serverneustart hinweg bleiben 3 + 5 = 8 verworfene Ereignisse. Gelesen: $n. " +
                "−8 oder 5 = jemand rechnet mit `total` (nicht monoton) · 18 = Summe der Totals · " +
                "2 = Anzahl der Meldungen.",
        )
    }

    // ---------------------------------------------------------------- Leitplanken für den Fix

    /** Keine Lücke → keine Zeile. Ein „0 Event(s) verworfen" wäre eine Meldung über nichts. */
    @Test
    fun withoutAnyDrop_thereIsNoGapLine_andTheSectionStillReportsRealDefects() = runBlocking {
        val f = Fix()
        f.sink.appendBatch(listOf(
            EventDraft("backend", "default", EventType.ERROR_TOOL, Severity.ERROR, correlationId = "c-1"),
        ))
        val items = f.gen.build(ReportType.DEFECTS, null, null).sections.first { it.key == "defects" }.items

        // Vorbedingung, damit die Abwesenheit unten etwas bedeutet (CYP-340): der Abschnitt hat wirklich Inhalt.
        assertTrue(items.any { it.refLabel == "correlationId: c-1" }, "Vorbedingung: echte Defekte werden gemeldet")
        assertFalse(items.any { it.refLabel == "log.dropped" }, "ohne Drop keine Lücken-Zeile")
    }

    /**
     * Der Fix **muss** `detail` lesen — der `ReportGenerator`-KDoc sagt heute *„NEVER from `Event.detail`"*.
     * Diese Leitplanke hält den Unterschied fest: **eine Zahl lesen ist erlaubt, den Inhalt ausschütten nicht.**
     */
    @Test
    fun readingTheCounterFromDetail_neverLeaksTheRestOfDetail() = runBlocking {
        val f = Fix()
        val needle = "sk-LEAK-9999"
        f.sink.appendBatch(listOf(EventDraft(
            "platform", "default", EventType.LOG_DROPPED, Severity.WARN,
            detail = buildJsonObject { put("dropped", 3); put("total", 3); put("secret", needle) },
        )))
        val built = f.gen.build(ReportType.DEFECTS, null, null)
        val all = built.sections.flatMap { it.items }.joinToString(" ") { it.text + " " + (it.refLabel ?: "") }

        assertTrue(all.contains("log.dropped"), "Vorbedingung: die Lücken-Zeile existiert überhaupt")
        assertFalse(all.contains(needle), "der Report darf aus detail NUR den Zähler lesen, nie den Rest")
    }
}

/**
 * CYP-353, **fünfter Befund (QA, nicht im Ticket)** — die Telemetrie-Lücke erreicht den Report **nie**.
 *
 * `EventRecorder.reportDropsIfAny()` schreibt `log.dropped` mit `projectId = PLATFORM` (`"platform"`).
 * `ReportGenerator.query()` filtert seit CYP-255 auf `state.activeProjectId` (`"default"`).
 * **`"platform" != "default"` ⇒ das Event wird herausgefiltert, bevor irgendetwas gezählt wird.**
 *
 * Die falsche Zählung in `:103` ist damit ein Fehler **hinter** einer Unsichtbarkeit: Ob dort `count` oder
 * `sum` steht, ändert im echten Betrieb nichts — die Zeile erscheint gar nicht. Der Kommentar darüber lautet
 * *„log.dropped is itself an observation gap — surface it honestly, not as a clean bill"*, und genau das tut
 * er heute nicht: **Er stellt einen sauberen Befund aus, weil er die Lücke nicht sieht.**
 *
 * Dieser Test fährt den **echten** Recorder gegen einen gestauten Sink, erzwingt echte Drops und liest den
 * Report über denselben Sink — kein nachgebautes Event. *(Die Lektion aus CYP-351: POSIX nachweisen ist nicht
 * unser System nachweisen.)*
 *
 * **Heute rot.** Und er ist die Vorbedingung dafür, dass die Fälle 1 und 2 im Betrieb überhaupt etwas messen.
 */
class Cyp353TelemetryGapNeverReachesTheReportTest {

    @Test
    fun realDroppedEventsAreScopedToPlatform_andTheProjectScopedReportNeverSeesThem() = runBlocking {
        val time = ManualTimeSource(start = 1_000L)
        val sink = InMemoryEventSink(time)
        val latched = LatchableEventSink(sink)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val recorder = EventRecorder(latched, scope, capacity = 1, batchSize = 1)
        recorder.start()

        // Sink stauen, Warteschlange überfüllen -> echte Drops, kein nachgebautes Event.
        latched.pause()
        repeat(50) { recorder.record(EventDraft("backend", "default", EventType.TURN_START, Severity.INFO)) }
        latched.resume()
        recorder.stop()

        // Vorbedingung: es wurde wirklich etwas verworfen, sonst prüft der Test nichts.
        assertTrue(recorder.dropped > 0, "Vorbedingung: der Recorder muss unter Rückstau wirklich verwerfen")

        val written = sink.all()
        val gap = written.firstOrNull { it.type == EventType.LOG_DROPPED }
        assertNotNull(gap, "Vorbedingung: der Recorder meldet die Lücke in den Sink")
        assertEquals("platform", gap.projectId, "Vorbedingung dieses Befunds: die Meldung ist platform-scoped")

        val state = HubState.hubAndSpoke(
            listOf(Agent("po", "PO", Role.PO, "po"), Agent("backend", "BE", Role.WORKER, "backend")),
            HubState.OPERATOR_ID, "default",
        )
        val gen = ReportGenerator(sink, state, Hub(state, InMemoryMessageStore()))
        val items = gen.build(ReportType.DEFECTS, null, null).sections.first { it.key == "defects" }.items

        assertTrue(
            items.any { it.refLabel == "log.dropped" },
            "Die Lücke steht im Event-Log (projectId=platform), aber der projekt-gefilterte Report " +
                "(activeProjectId=default) sieht sie nie. Der Report stellt einen sauberen Befund aus, " +
                "weil ihm die Beobachtung fehlt — dieselbe Klasse wie CYP-351.",
        )
    }
}
