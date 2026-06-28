package com.tneff.cyppieagents.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Product-Lead report wire contract (S16 / CYP-89 — PRODUCT-LEAD §2). The DTOs live in `:core` so the
 * server generator and the client `ReportRepository` compile against ONE definition (reconciled from
 * `:app:shared` at CYP-89). Severity reuses [Severity] (the Event-Log rail, CYP-34).
 *
 * **Content-free by structure (Reviewer hard-enforcement):** a [ReportItem] carries a short composed
 * observation ([text]), a [severity], and a non-sensitive [refLabel] (e.g. `"agent: frontend"`) — there
 * is **no field that can transport an event/message body**. A snapshot is immutable + timestamped (the
 * `as-of` time, snapshot ≠ live); the defect register is advisory (observed, not exhaustive/confirmed).
 */

/** The three "where do we stand?" report types (PRODUCT-LEAD §2). */
@Serializable
enum class ReportType {
    @SerialName("usage") USAGE,
    @SerialName("status") STATUS,
    @SerialName("defects") DEFECTS,
}

/** POST /api/reports body: which snapshot to generate, with an optional observation window. */
@Serializable
data class GenerateReportRequest(
    val type: ReportType,
    val since: Long? = null,
    val until: Long? = null,
)

/** Lightweight list entry (GET /api/reports, newest first). [generatedAt] = the snapshot's as-of epoch ms. */
@Serializable
data class ReportMeta(
    val id: String,
    val type: ReportType,
    val generatedAt: Long,
    val summary: String,
)

/** An immutable, timestamped platform snapshot (GET /api/reports/{id}). */
@Serializable
data class ReportSnapshot(
    val id: String,
    val type: ReportType,
    val generatedAt: Long,
    val projectId: String,
    /** Named READ sources this snapshot folded (provenance, not authoritative). */
    val sources: List<String>,
    /** Observation window labels, or null when unbounded. */
    val window: ReportWindow,
    val sections: List<ReportSection>,
)

/** Observation window (display labels). Honest "observed over this window", not "all time". */
@Serializable
data class ReportWindow(val sinceLabel: String? = null, val untilLabel: String? = null)

@Serializable
data class ReportSection(val key: String, val title: String, val items: List<ReportItem>)

/**
 * One observed item. **Content-free:** [text] is a short observation the generator composes from event
 * **metadata** (type/severity/count) — never a body; [severity] drives the defect-register rail; [refLabel]
 * is a non-sensitive ref (`"agent: frontend"`, `"correlationId: c-1"`) — never an event body or message text.
 */
@Serializable
data class ReportItem(val text: String, val severity: Severity? = null, val refLabel: String? = null)
