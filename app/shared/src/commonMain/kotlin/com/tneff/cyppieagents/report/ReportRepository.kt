package com.tneff.cyppieagents.report

import com.tneff.cyppieagents.model.Severity

/**
 * Product-Lead report data port (S16, CYP-90 UI) — the contract from `docs/PRODUCT-LEAD.md §2` that the
 * **backend must still build** (greenfield, tracked as CYP-89). The UI depends only on this abstraction,
 * so the live REST client is a later **stub→real swap** with no UI change. Plain Kotlin types (the wire
 * DTOs are `:core`/backend territory, reconciled at CYP-89); severity reuses the shared [Severity].
 *
 * **Reads, builds nothing — no guarantee.** A report is an immutable, timestamped **snapshot per run**;
 * generating never mutates a prior one. The list is a history of snapshots — none is "the" status.
 * Operator-gated/fail-closed: aggregating operator-gated observability means a viewer without a token
 * gets **no report at all** (not a partial), and the items stay content-free (refs/metadata, no bodies).
 */
interface ReportRepository {
    /** Snapshot metadata, newest first (`GET /api/reports`). */
    suspend fun list(): List<ReportMeta>

    /** The full snapshot (`GET /api/reports/{id}`). Throws [ReportException] `report_not_found`. */
    suspend fun get(id: String): ReportSnapshot

    /** Operator-only: generate a NEW snapshot of [type] (`POST /api/reports`). Immutable, fresh per run. */
    suspend fun generate(type: ReportType): ReportSnapshot
}

/** The three "where do we stand?" report types (PRODUCT-LEAD §2). */
enum class ReportType { USAGE, STATUS, DEFECTS }

/** Lightweight list entry (newest first). [generatedAt] = the snapshot's as-of epoch ms (display + order). */
data class ReportMeta(
    val id: String,
    val type: ReportType,
    val generatedAt: Long,
    val summary: String,
)

/**
 * An immutable, timestamped platform snapshot (PRODUCT-LEAD §2). [generatedAt] is the prominent
 * "as of" time (snapshot ≠ live); [sources]/[window] are the provenance; [sections] hold the observed,
 * content-free items.
 */
data class ReportSnapshot(
    val id: String,
    val type: ReportType,
    val generatedAt: Long,
    val projectId: String,
    /** Named READ sources this snapshot folded (provenance, not authoritative). */
    val sources: List<String>,
    /** Observation window labels (`since`/`until`), or null when unbounded. */
    val window: ReportWindow,
    val sections: List<ReportSection>,
)

/** Observation window (display labels). Honest "observed over this window", not "all time". */
data class ReportWindow(val sinceLabel: String? = null, val untilLabel: String? = null)

data class ReportSection(val key: String, val title: String, val items: List<ReportItem>)

/**
 * One observed item. Content-free: [text] is a short observation, [severity] drives the defect-register
 * rail (reuse CYP-34), [refLabel] is a non-sensitive ref (e.g. "agent: frontend") — never an event body.
 */
data class ReportItem(val text: String, val severity: Severity? = null, val refLabel: String? = null)

/**
 * A report call was rejected. [code] is the server reason — the wire codes from PRODUCT-LEAD §2:
 * `invalid_report_type`, `report_not_found`, `operator_required` (403), `unauthorized` (401).
 */
class ReportException(val code: String) : Exception(code)
