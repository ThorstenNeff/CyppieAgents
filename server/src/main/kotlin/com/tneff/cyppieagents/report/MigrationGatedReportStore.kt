package com.tneff.cyppieagents.report

import com.tneff.cyppieagents.db.BindingReason

import com.tneff.cyppieagents.boot.storeMigrating
import com.tneff.cyppieagents.model.GenerateReportRequest
import com.tneff.cyppieagents.model.ReportMeta
import com.tneff.cyppieagents.model.ReportSnapshot

/**
 * CYP-220 Phase 6 S6 — the read-only-window gate for [ReportStore] (the S3 migration-freeze, kept universal):
 * during a `report` binding's MIGRATING/READ_ONLY window, browse ([list]/[get]) passes through to source A, but
 * [generate] (the only write) is rejected ([storeMigrating] → 409), so a snapshot generated mid-migration can
 * never be lost in A or duplicated into B.
 */
class MigrationGatedReportStore(private val sourceA: ReportStore, private val reason: BindingReason) : ReportStore {
    override suspend fun generate(req: GenerateReportRequest): ReportSnapshot = throw storeMigrating("report", reason)
    override fun list(): List<ReportMeta> = sourceA.list()
    override fun get(id: String): ReportSnapshot = sourceA.get(id)
}
