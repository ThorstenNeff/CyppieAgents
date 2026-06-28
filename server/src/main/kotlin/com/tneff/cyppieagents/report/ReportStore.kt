package com.tneff.cyppieagents.report

import com.tneff.cyppieagents.model.GenerateReportRequest
import com.tneff.cyppieagents.model.ReportMeta
import com.tneff.cyppieagents.model.ReportSnapshot
import com.tneff.cyppieagents.routing.NotFoundException

/**
 * Holds the immutable report snapshots (S16 / CYP-89), per active project (MVP = 1). Each [generate]
 * appends a NEW snapshot with a monotonic id + a fresh server-clock `generatedAt` — a prior snapshot is
 * never mutated or merged. [list] is newest-first history; none is "the" status. Stamping (id + time)
 * is owned here in one place; the [ReportGenerator] only builds content-free sections.
 */
class ReportStore(
    private val generator: ReportGenerator,
    private val projectId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val snapshots = mutableListOf<ReportSnapshot>()
    private var counter = 0

    suspend fun generate(req: GenerateReportRequest): ReportSnapshot {
        val built = generator.build(req.type, req.since, req.until) // reads sources OUTSIDE the lock
        return synchronized(lock) {
            val snap = ReportSnapshot(
                id = "rep-${counter++}",
                type = req.type,
                generatedAt = clock(),
                projectId = projectId,
                sources = built.sources,
                window = built.window,
                sections = built.sections,
            )
            snapshots.add(snap)
            snap
        }
    }

    fun list(): List<ReportMeta> = synchronized(lock) {
        snapshots.sortedByDescending { it.generatedAt }
            .map { ReportMeta(it.id, it.type, it.generatedAt, summaryOf(it)) }
    }

    fun get(id: String): ReportSnapshot {
        val snap = synchronized(lock) { snapshots.firstOrNull { it.id == id } }
        return snap ?: throw NotFoundException("report '$id' not found", code = "report_not_found")
    }

    private fun summaryOf(s: ReportSnapshot): String =
        s.sections.sumOf { it.items.size }.let { "$it Beobachtungen" }
}
