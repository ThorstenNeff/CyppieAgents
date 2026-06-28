package com.tneff.cyppieagents.report
import com.tneff.cyppieagents.model.ReportWindow
import com.tneff.cyppieagents.model.ReportType
import com.tneff.cyppieagents.model.ReportSnapshot
import com.tneff.cyppieagents.model.ReportSection
import com.tneff.cyppieagents.model.ReportMeta
import com.tneff.cyppieagents.model.ReportItem

import com.tneff.cyppieagents.model.Severity

/**
 * In-memory [ReportRepository] for ungated development and tests (S16) until the backend seam (CYP-89)
 * lands — then the live REST client replaces this with no UI change. Honest about the feature's nature:
 *
 * - **Immutable history:** each [generate] appends a NEW snapshot (monotonic id + as-of time); none is
 *   ever mutated. [list] returns newest-first — a history, not "the" status.
 * - **Content-free, observed:** items carry short observations + refs + severity, never event bodies;
 *   the defect register is advisory (not exhaustive/confirmed), including an honest `log.dropped`
 *   "observation gap" line.
 * - **Reads, builds nothing.** The stub fabricates representative content; an optional [denyWrites]
 *   models the operator gate (server 403) for the fail-closed test.
 *
 * No wall clock in `commonMain`, so the as-of time advances from [baseTime] by a fixed step per run —
 * deterministic for tests while still strictly increasing (newest-first ordering is real).
 */
class StubReportRepository(
    private val baseTime: Long = 1_700_000_000_000L,
    private val projectId: String = "default",
    private val denyWrites: String? = null,
) : ReportRepository {

    private val snapshots = mutableListOf<ReportSnapshot>()
    private var counter = 0

    override suspend fun list(): List<ReportMeta> =
        snapshots.sortedByDescending { it.generatedAt }.map { snap ->
            ReportMeta(snap.id, snap.type, snap.generatedAt, summaryOf(snap))
        }

    override suspend fun get(id: String): ReportSnapshot =
        snapshots.firstOrNull { it.id == id } ?: throw ReportException("report_not_found")

    override suspend fun generate(type: ReportType): ReportSnapshot {
        denyWrites?.let { throw ReportException(it) }
        val n = counter++
        val at = baseTime + n * 60_000L // strictly increasing as-of time, no real clock needed
        val snap = ReportSnapshot(
            id = "rep-$n",
            type = type,
            generatedAt = at,
            projectId = projectId,
            sources = sourcesFor(type),
            window = ReportWindow(sinceLabel = "boot", untilLabel = "now"),
            sections = sectionsFor(type),
        )
        snapshots.add(snap)
        return snap
    }

    private fun summaryOf(snap: ReportSnapshot): String =
        snap.sections.sumOf { it.items.size }.let { "$it Beobachtungen" }

    private fun sourcesFor(type: ReportType): List<String> = when (type) {
        ReportType.USAGE -> listOf("agents", "channels")
        ReportType.STATUS -> listOf("events:turn.*/result.final/agent.*", "inbox:STATUS")
        ReportType.DEFECTS -> listOf("events:error.*/timeout/process.exit/log.dropped")
    }

    private fun sectionsFor(type: ReportType): List<ReportSection> = when (type) {
        ReportType.USAGE -> listOf(
            ReportSection(
                "setup", "Setup",
                listOf(
                    ReportItem("3 Agenten registriert (1 PO, 2 Worker)", refLabel = "agents"),
                    ReportItem("Hub-and-Spoke-Kanäle aktiv", refLabel = "channels"),
                ),
            ),
        )
        ReportType.STATUS -> listOf(
            ReportSection(
                "activity", "Aktivität (beobachtet)",
                listOf(
                    ReportItem("frontend: letzter Turn abgeschlossen", refLabel = "agent: frontend"),
                    ReportItem("backend: läuft", refLabel = "agent: backend"),
                ),
            ),
        )
        ReportType.DEFECTS -> listOf(
            ReportSection(
                "defects", "Beobachtete Defekte/Lücken",
                listOf(
                    ReportItem("Tool-Fehler beobachtet", severity = Severity.ERROR, refLabel = "correlationId: c-1"),
                    ReportItem("Rate-Limit gestreift", severity = Severity.WARN, refLabel = "agent: backend"),
                    // Honest telemetry gap — log.dropped is itself an observation gap, not a clean bill.
                    ReportItem("Telemetrie-Lücke: Events verworfen (log.dropped)", severity = Severity.INFO, refLabel = "log.dropped"),
                ),
            ),
        )
    }
}
