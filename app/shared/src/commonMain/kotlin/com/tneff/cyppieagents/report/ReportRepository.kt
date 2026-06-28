package com.tneff.cyppieagents.report

import com.tneff.cyppieagents.model.ReportMeta
import com.tneff.cyppieagents.model.ReportSnapshot
import com.tneff.cyppieagents.model.ReportType

/**
 * Product-Lead report data port (S16, CYP-90 UI). The wire DTOs ([ReportType]/[ReportMeta]/
 * [ReportSnapshot]/…) now live in `:core` (reconciled at CYP-89), so the live REST client is a
 * stub→real swap with no UI change.
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

/**
 * A report call was rejected. [code] is the server reason — the wire codes from PRODUCT-LEAD §2:
 * `invalid_report_type`, `report_not_found`, `operator_required` (403), `unauthorized` (401).
 */
class ReportException(val code: String) : Exception(code)
