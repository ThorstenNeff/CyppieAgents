package com.tneff.cyppieagents.report

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.events.EventDraft
import com.tneff.cyppieagents.events.EventFilter
import com.tneff.cyppieagents.events.EventRecorder
import com.tneff.cyppieagents.events.InMemoryEventSink
import com.tneff.cyppieagents.events.LatchableEventSink
import com.tneff.cyppieagents.events.ManualTimeSource
import com.tneff.cyppieagents.events.Page
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.ReportType
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.routing.EVENT_SCOPE_ALL
import com.tneff.cyppieagents.routing.resolveEventScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-364 (QA) — **Ist `log.dropped` ein Plattform- oder ein Projekt-Ereignis?**
 *
 * Die Frage entscheidet den Fix, und der Code beantwortet sie. Nicht die Absicht, nicht der Name: **was der
 * Code tut.**
 *
 * ## Die Messung
 *
 * 1. **`PLATFORM` bedeutet in beiden Dateien dasselbe** — kein zufällig gleicher String.
 *    `SpoolReader.kt:93` sagt es wörtlich: *„else the PLATFORM sentinel (platform-internal hook event,
 *    **no specific project**)"*. Dort ist es der **Fallback**, wenn eine Spool-Zeile keine `projectId` trägt.
 *    `EventRecorder.kt:123`: *„System source id for **platform-level** telemetry (e.g. `log.dropped`)."*
 *    Beide benutzen `"platform"` als **Sentinel für „gehört zu keinem Projekt"**.
 *
 * 2. **Es gibt genau einen `EventRecorder` pro Server** (`BootOrchestrator.kt:288`, Kommentar: *„one
 *    EventRecorder feeds the shared sink"*). In **seine eine Warteschlange** schreiben mindestens vier Quellen
 *    mit **verschiedenen** `projectId`s: `HubWireRoutes` (aktives Projekt), `SignalSink` (`signal.projectId`),
 *    `SpoolTailer` (pro Spool-Zeile), `ConnectorOptIn`.
 *
 * 3. Also zählt `droppedCount` Verwürfe **über alle Projekte hinweg**. Ein Verwurf ist **keinem Projekt
 *    zurechenbar** — welches Projekt das verlorene Ereignis betraf, weiß nach dem Verwurf niemand mehr.
 *
 * ## Die Antwort
 *
 * **`log.dropped` IST ein Plattform-Ereignis. `EventRecorder` schreibt den richtigen `projectId`.**
 * Ihn auf das aktive Projekt zu stempeln, wäre eine *Erfindung*: Der Verwurf könnte ein fremdes Projekt
 * betroffen haben. **Der Defekt sitzt auf der Leseseite** — `ReportGenerator` muss Plattform-Ereignisse
 * **zusätzlich** zum aktiven Projekt einbeziehen.
 *
 * Der Beleg dafür steht im Lesepfad selbst: `/api/events` und `/ws/events` haben über
 * `resolveEventScope(… , EVENT_SCOPE_ALL, …)` einen **unscoped** Ausweg und **sehen** die Meldung.
 * `ReportGenerator.kt:123` verdrahtet `projectId = state.activeProjectId` **fest** und hat keinen.
 * **Derselbe Event-Log, zwei Leser, nur einer ist blind.**
 */
class Cyp364PlatformScopeTest {

    private fun state() = HubState.hubAndSpoke(
        listOf(Agent("po", "PO", Role.PO, "po"), Agent("backend", "BE", Role.WORKER, "backend")),
        HubState.OPERATOR_ID, "default",
    )

    /**
     * **Messung 1:** Ein Verwurf ist keinem Projekt zurechenbar. Drei Projekte speisen **eine** Warteschlange;
     * was verworfen wird, entscheidet der Rückstau, nicht das Projekt. Die Meldung trägt deshalb den Sentinel
     * und **keinerlei** Projekt-Zuordnung.
     */
    @Test
    fun dropsAreProjectAgnostic_soTheReportEventCarriesTheSentinel_notAProject() = runBlocking {
        val sink = InMemoryEventSink(ManualTimeSource(start = 1_000L))
        val latched = LatchableEventSink(sink)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recorder = EventRecorder(latched, scope, capacity = 1, batchSize = 1).also { it.start() }

        latched.pause()
        // Drei verschiedene Projekte in DIESELBE Warteschlange — genau wie HubWireRoutes/SignalSink/SpoolTailer.
        listOf("default", "alpha", "beta").forEach { p ->
            repeat(20) { recorder.record(EventDraft("backend", p, EventType.TURN_START, Severity.INFO)) }
        }
        latched.resume()
        recorder.stop()

        assertTrue(recorder.dropped > 0, "Vorbedingung: unter Rückstau muss wirklich verworfen werden")

        val gap = sink.all().firstOrNull { it.type == EventType.LOG_DROPPED }
        assertNotNull(gap, "Vorbedingung: der Recorder meldet die Lücke")

        assertEquals(
            "platform", gap.projectId,
            "Der Verwurf traf Ereignisse aus 'default', 'alpha' UND 'beta'. Ihn einem davon zuzuschreiben " +
                "wäre eine Erfindung — der Sentinel ist die einzige ehrliche Zuordnung.",
        )
        assertNull(
            gap.detail["projectId"],
            "die Meldung darf kein Projekt behaupten: nach dem Verwurf weiß niemand mehr, welches betroffen war",
        )
    }

    /**
     * **Messung 2:** Der Sentinel ist **kein anforderbarer Projektname**. Selbst ein Operator kann ihn nicht
     * per `?projectId=platform` wählen — er ist nie in `authorizedProjects`, also fällt der Resolver
     * fail-closed auf das aktive Projekt zurück. **Der einzige Weg zu Plattform-Ereignissen ist `unscoped`.**
     */
    @Test
    fun theSentinelIsNotARequestableProject_onlyTheUnscopedPathReachesIt() {
        val authorized = setOf("default", "alpha")

        assertEquals(
            "default", resolveEventScope("platform", active = "default", authorizedProjects = authorized),
            "'platform' ist kein Projekt: der Resolver fällt fail-closed auf das aktive zurück",
        )
        assertNull(
            resolveEventScope(EVENT_SCOPE_ALL, active = "default", authorizedProjects = authorized),
            "nur EVENT_SCOPE_ALL löst auf unscoped (null) auf — und nur unscoped sieht den Sentinel",
        )
        assertEquals(
            "default", resolveEventScope(null, active = "default", authorizedProjects = authorized),
            "ohne Wunsch: aktives Projekt — der Default jedes Lesers",
        )
    }

    /**
     * **Messung 3 — die Asymmetrie, und der Guard, den CYP-364 verlangt.**
     *
     * Derselbe Sink, dasselbe Ereignis, zwei Leser. Der **unscoped** Lesepfad (den `/api/events` einem
     * Operator über `?projectId=all` öffnet) sieht die Lücke. Der projekt-gefilterte Lesepfad — und damit der
     * Report — sieht sie nicht.
     *
     * **Die Vorbedingung ist positiv belegt**, bevor irgendeine Abwesenheit geprüft wird: das Ereignis liegt
     * nachweislich im Sink und ist über `projectId = null` abrufbar. Ohne diesen Nachweis wäre die
     * Abwesenheits-Assertion wieder aus dem falschen Grund grün — ein leerer Sink „besteht" jeden Test.
     *
     * **Heute rot.** Die letzte Assertion ist die Zusage von CYP-364.
     */
    @Test
    fun theEventBrowserSeesTheGap_butTheProjectScopedReportSwallowsIt() = runBlocking {
        val sink = InMemoryEventSink(ManualTimeSource(start = 1_000L))

        // Exakt so, wie EventRecorder.reportDropsIfAny() schreibt (agentId + projectId = PLATFORM).
        sink.appendBatch(listOf(EventDraft("platform", "platform", EventType.LOG_DROPPED, Severity.WARN)))
        // Ein gewöhnlicher Projekt-Defekt daneben, damit der Report nicht einfach leer ist.
        sink.appendBatch(listOf(EventDraft("backend", "default", EventType.ERROR_TOOL, Severity.ERROR, correlationId = "c-1")))

        // --- Vorbedingung 1: das Ereignis liegt im Sink.
        val unscoped = sink.query(EventFilter(projectId = null), Page(limit = 100)).events
        assertTrue(
            unscoped.any { it.type == EventType.LOG_DROPPED },
            "Vorbedingung: der unscoped Leser (Operator via ?projectId=all) sieht die Lücke",
        )

        // --- Vorbedingung 2: der projekt-gefilterte Leser sieht sie nicht. Das ist der Mechanismus, nicht der Defekt.
        val scoped = sink.query(EventFilter(projectId = "default"), Page(limit = 100)).events
        assertTrue(
            scoped.none { it.type == EventType.LOG_DROPPED },
            "Vorbedingung dieses Befunds: der projekt-gefilterte Leser verschluckt den Sentinel",
        )
        assertTrue(scoped.any { it.type == EventType.ERROR_TOOL }, "Vorbedingung: er sieht die Projekt-Ereignisse sehr wohl")

        // --- Der Defekt: der Report benutzt genau diesen blinden Leser.
        val st = state()
        val gen = ReportGenerator(sink, st, Hub(st, InMemoryMessageStore()))
        val items = gen.build(ReportType.DEFECTS, null, null).sections.first { it.key == "defects" }.items

        assertTrue(items.any { it.refLabel == "correlationId: c-1" }, "Vorbedingung: der Report meldet echte Defekte")
        assertTrue(
            items.any { it.refLabel == "log.dropped" },
            "Die Telemetrie-Lücke liegt im Event-Log und ist unscoped sichtbar, aber der Report filtert auf " +
                "activeProjectId='default' und verschluckt sie. `if (dropped > 0)` ist nie wahr ⇒ die Zeile " +
                "erscheint nie ⇒ ihre ABWESENHEIT wird als 'nichts verworfen' gelesen. " +
                "Der Report muss Plattform-Ereignisse ZUSÄTZLICH zum aktiven Projekt einbeziehen.",
        )
    }

    /**
     * **Die Leitplanke gegen die naheliegende Über-Korrektur:** „dann filtern wir im Report eben gar nicht".
     * CYP-255 hat den Projekt-Filter aus einem Grund eingezogen — ein unscoped Report aggregiert über **fremde
     * Projekte** (Cross-Project-Egress). Der Fix muss `platform` **zusätzlich** einlassen, nicht die Grenze
     * niederreißen.
     *
     * Dieser Test ist **heute grün** und bleibt es nur, solange der Fix eng bleibt. Er ist der Grund, warum
     * `projectId = null` im Report **keine** Lösung ist.
     */
    @Test
    fun theFixMustNotUnscopeTheReport_foreignProjectsStayOut() = runBlocking {
        val sink = InMemoryEventSink(ManualTimeSource(start = 1_000L))
        sink.appendBatch(listOf(
            EventDraft("backend", "default", EventType.ERROR_TOOL, Severity.ERROR, correlationId = "mine"),
            EventDraft("backend", "alpha", EventType.ERROR_TOOL, Severity.ERROR, correlationId = "foreign"),
        ))
        val st = state()
        val gen = ReportGenerator(sink, st, Hub(st, InMemoryMessageStore()))
        val items = gen.build(ReportType.DEFECTS, null, null).sections.first { it.key == "defects" }.items

        assertTrue(items.any { it.refLabel == "correlationId: mine" }, "Vorbedingung: das eigene Projekt erscheint")
        assertTrue(
            items.none { it.refLabel == "correlationId: foreign" },
            "CYP-255: ein fremdes Projekt darf NIE im Report des aktiven auftauchen. " +
                "Der CYP-364-Fix lässt 'platform' zusätzlich zu — er hebt den Filter nicht auf.",
        )
    }
}
