package com.tneff.cyppieagents.report

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.events.EventDraft
import com.tneff.cyppieagents.events.EventFilter
import com.tneff.cyppieagents.events.InMemoryEventSink
import com.tneff.cyppieagents.events.ManualTimeSource
import com.tneff.cyppieagents.events.Page
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.ReportItem
import com.tneff.cyppieagents.model.ReportType
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.Severity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CYP-364 (QA) — **Der Trennversuch für die Leseseite.** Alle drei Fälle sind heute rot.
 *
 * Die Messung (`Cyp364PlatformScopeTest`) hat die Frage beantwortet: `log.dropped` ist ein **Plattform**-
 * Ereignis, die Schreibseite ist korrekt, der Defekt sitzt in [ReportGenerator]. Dieser Test hält die drei
 * Zusagen fest, die der Fix einlösen muss.
 *
 * ## Warum Fall 3 den ganzen Pfad geht
 *
 * **CYP-364 und CYP-353 liegen hintereinander auf demselben Pfad:**
 *
 * ```
 * Sink ──(Projekt-Filter: CYP-364)──> events ──(count statt sum: CYP-353)──> Zeile
 * ```
 *
 * Ein Test, der den Filter umgeht und direkt auf `events` rechnet, prüft nur die Arithmetik und **übersieht,
 * dass sie nie ein Argument bekommt**. Deshalb baut Fall 3 seine Erwartung über den **echten**
 * `ReportGenerator.build(...)` — nicht über eine nachgerechnete Liste.
 *
 * ## Warum die Beschriftung ein eigener Fall ist
 *
 * Der Verwurf-Zähler ist **server-weit**: ein `EventRecorder` pro Server, vier Quellen mit verschiedenen
 * `projectId`s in **einer** Warteschlange, und der **Rückstau** entscheidet, was verworfen wird — nicht das
 * Projekt. `ReportSnapshot` trägt aber ein Feld `projectId`: **das Dokument weist sich selbst als
 * Projekt-Report aus.** Eine unbeschriftete server-weite Zahl darin liest sich als „in diesem Projekt
 * verworfen".
 *
 * **Wer nur die Zahl prüft, lässt die falsche Beschriftung durch — und schiebt die Ableitung bloß vom Filter
 * an die Anzeige.** Der Filter-Fix erzeugte dann sauber die nächste Lüge.
 *
 * > Lieber eine unbequeme Zahl mit richtigem Bezugsrahmen als eine bequeme mit falschem.
 */
class Cyp364ReadPathTest {

    /**
     * **Test-Contract (Wortschatz).** Der Fix darf formulieren, wie er will — die Zeile muss ihren
     * Bezugsrahmen aber *nennen*. Akzeptiert wird eines dieser Wörter. **Wer den Wortschatz erweitert, ändert
     * ihn hier sichtbar** — er weicht ihn nicht still auf, indem er ein neues Wort in die Produktion schreibt.
     */
    private val platformWideMarkers = listOf("plattformweit", "serverweit", "server-weit", "alle projekte")

    private fun fix(activeProject: String = "default"): Pair<InMemoryEventSink, ReportGenerator> {
        val sink = InMemoryEventSink(ManualTimeSource(start = 1_000L))
        val state = HubState.hubAndSpoke(
            listOf(Agent("po", "PO", Role.PO, "po"), Agent("backend", "BE", Role.WORKER, "backend")),
            HubState.OPERATOR_ID, activeProject,
        )
        return sink to ReportGenerator(sink, state, Hub(state, InMemoryMessageStore()))
    }

    /** Genau wie `EventRecorder.reportDropsIfAny()` schreibt: agentId **und** projectId = PLATFORM. */
    private fun realDropReport(delta: Int, total: Int) = EventDraft(
        agentId = "platform", projectId = "platform", type = EventType.LOG_DROPPED, severity = Severity.WARN,
        detail = buildJsonObject { put("dropped", delta); put("total", total) },
    )

    private suspend fun gapItem(gen: ReportGenerator, since: Long? = null): ReportItem? =
        gen.build(ReportType.DEFECTS, since, null).sections.first { it.key == "defects" }
            .items.firstOrNull { it.refLabel == "log.dropped" }

    /** Geparst, nicht gesucht: `contains("8")` ist auch für `18` wahr — einen der falschen Werte aus CYP-353. */
    private fun leadingNumber(text: String): Int? = Regex("""\d+""").find(text)?.value?.toInt()

    // ---------------------------------------------------------------- Fall 1: Sichtbarkeit

    @Test
    fun aPlatformScopedDropEvent_reachesTheReportOfAnyActiveProject() = runBlocking<Unit> {
        val (sink, gen) = fix(activeProject = "default")
        sink.appendBatch(listOf(realDropReport(delta = 4, total = 4)))
        sink.appendBatch(listOf(EventDraft("backend", "default", EventType.ERROR_TOOL, Severity.ERROR, correlationId = "c-1")))

        // Vorbedingung positiv belegt: das Ereignis liegt im Sink und ist unscoped abrufbar.
        val unscoped = sink.query(EventFilter(projectId = null), Page(limit = 100)).events
        assertTrue(unscoped.any { it.type == EventType.LOG_DROPPED }, "Vorbedingung: die Meldung liegt im Sink")

        // Vorbedingung: der Report ist nicht einfach leer.
        val items = gen.build(ReportType.DEFECTS, null, null).sections.first { it.key == "defects" }.items
        assertTrue(items.any { it.refLabel == "correlationId: c-1" }, "Vorbedingung: der Report meldet echte Defekte")

        assertNotNull(
            items.firstOrNull { it.refLabel == "log.dropped" },
            "Das aktive Projekt ist 'default', die Meldung trägt den Sentinel 'platform' und wird vom " +
                "Projekt-Filter verschluckt. `if (dropped > 0)` ist nie wahr ⇒ die Zeile erscheint nie ⇒ " +
                "ihre ABWESENHEIT wird als 'nichts verworfen' gelesen.",
        )
    }

    /** Auch mit einem anderen aktiven Projekt: der Sentinel gehört keinem — er gehört zu **jedem** Report. */
    @Test
    fun theSentinelIsVisibleRegardlessOfWhichProjectIsActive() = runBlocking<Unit> {
        val (sink, gen) = fix(activeProject = "alpha")
        sink.appendBatch(listOf(realDropReport(delta = 7, total = 7)))

        val unscoped = sink.query(EventFilter(projectId = null), Page(limit = 100)).events
        assertTrue(unscoped.any { it.type == EventType.LOG_DROPPED }, "Vorbedingung: die Meldung liegt im Sink")

        assertNotNull(gapItem(gen), "aktives Projekt 'alpha' — der Verwurf betraf möglicherweise genau es")
    }

    // ---------------------------------------------------------------- Fall 2: Bezugsrahmen

    @Test
    fun theRenderedLine_declaresTheNumberAsPlatformWide_notAsThisProjects() = runBlocking {
        val (sink, gen) = fix(activeProject = "default")
        sink.appendBatch(listOf(realDropReport(delta = 4, total = 4)))

        val item = assertNotNull(gapItem(gen), "Vorbedingung: die Lücken-Zeile existiert (sonst prüft der Test nichts)")
        val text = item.text.lowercase()

        assertTrue(
            platformWideMarkers.any { text.contains(it) },
            "Der Verwurf-Zähler ist SERVER-WEIT (ein EventRecorder, vier Quellen, eine Warteschlange; der " +
                "Rückstau entscheidet, nicht das Projekt). ReportSnapshot trägt aber ein Feld `projectId` — " +
                "das Dokument weist sich als Projekt-Report aus. Eine unbeschriftete Zahl darin liest sich als " +
                "'in diesem Projekt verworfen'. Gelesen: \"${item.text}\". " +
                "Erwartet eines von: $platformWideMarkers. " +
                "Wer nur die Zahl repariert, verschiebt die Ableitung vom Filter an die Anzeige.",
        )
    }

    // ---------------------------------------------------------------- Fall 3: beide Defekte, ein Pfad

    /**
     * Vorlast bis `total = 10` **vor** dem Fenster, dann Deltas 3 und 5 ⇒ **8** — gemessen **durch den echten
     * Filter hindurch**. Dieser Fall bleibt rot, solange **einer** der beiden Defekte lebt:
     *
     * * nur CYP-364 gefixt → Zeile da, aber sie sagt `2` (Anzahl der Meldungen).
     * * nur CYP-353 gefixt → Zeile fehlt ganz (die Summe hat nie ein Argument).
     *
     * **Er ist die einzige Assertion, die beweist, dass beide Fixes zusammen wirken.**
     */
    @Test
    fun throughTheRealFilter_theNumberIsTheSumOfDeltasInTheWindow() = runBlocking {
        val time = ManualTimeSource(start = 1_000L)
        val sink = InMemoryEventSink(time)
        val state = HubState.hubAndSpoke(
            listOf(Agent("po", "PO", Role.PO, "po"), Agent("backend", "BE", Role.WORKER, "backend")),
            HubState.OPERATOR_ID, "default",
        )
        val gen = ReportGenerator(sink, state, Hub(state, InMemoryMessageStore()))

        time.clock = 1_000L
        sink.appendBatch(listOf(realDropReport(delta = 10, total = 10))) // Vorlast, VOR dem Fenster
        time.clock = 2_000L
        sink.appendBatch(listOf(realDropReport(delta = 3, total = 13)))
        time.clock = 2_500L
        sink.appendBatch(listOf(realDropReport(delta = 5, total = 18)))

        // Vorbedingung: alle drei liegen im Sink (unscoped sichtbar).
        val all = sink.query(EventFilter(projectId = null), Page(limit = 100)).events
        assertEquals(3, all.count { it.type == EventType.LOG_DROPPED }, "Vorbedingung: drei Meldungen im Sink")

        val item = assertNotNull(
            gapItem(gen, since = 1_500L),
            "CYP-364 lebt noch: der Projekt-Filter verschluckt die plattform-scoped Meldungen, " +
                "die Summe bekommt nie ein Argument.",
        )
        assertEquals(
            8, leadingNumber(item.text),
            "Durch den echten Filter hindurch: 3 + 5 = 8 (die Vorlast 10 liegt vor dem Fenster). " +
                "2 = Anzahl der Meldungen (CYP-353 lebt) · 31 = Summe der Totals · 18 = letzter Total · " +
                "5 = letztes Delta. Gelesen: \"${item.text}\".",
        )
    }
}
