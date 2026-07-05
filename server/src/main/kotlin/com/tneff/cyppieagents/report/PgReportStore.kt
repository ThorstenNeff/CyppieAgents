package com.tneff.cyppieagents.report

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.db.FlywayMigrator
import com.tneff.cyppieagents.db.MigrationRowCodec
import com.tneff.cyppieagents.db.MigrationTarget
import com.tneff.cyppieagents.model.GenerateReportRequest
import com.tneff.cyppieagents.model.ReportMeta
import com.tneff.cyppieagents.model.ReportSnapshot
import com.tneff.cyppieagents.model.ReportType
import com.tneff.cyppieagents.routing.NotFoundException
import java.sql.Connection
import javax.sql.DataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CYP-220 Phase 6 S6 — the **Postgres** [ReportStore]. Behaviour-identical to [InMemoryReportStore]/
 * [FileReportStore]: **append-only** immutable snapshots (⇒ RMW-immune), the whole [ReportSnapshot] stored as
 * its canonical [CommJson] form (one `snapshot_json` column, S4 pattern — no field mapping); `list` newest-first
 * via the shared [reportMetas]. NON-SECRET (content-free observations), so no cipher. Low volume (operator-
 * generated, on demand) → no pagination.
 *
 * The `rep-N` id is stamped by an **in-process counter under [lock]**, resumed above the highest persisted id on
 * open (and realigned by [importRows]) — the same import-realign discipline as the event-store seqs. The lock
 * makes two concurrent [generate]s take **distinct** ids (no PK collision); the counter is process-local state,
 * so the live-wiring memoizes this store per DataSource (like the event stores), never per-op.
 */
class PgReportStore(
    private val generator: ReportGenerator,
    private val projectId: String,
    private val dataSource: DataSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    migrate: Boolean = true,
    /**
     * The section builder — defaults to the injected [generator]; overridable ONLY so the concurrency tooth can
     * barrier-align two `generate`s at the id-stamping step (the build step otherwise desyncs them, hiding the
     * counter race). Not a production seam beyond that.
     */
    private val buildFn: suspend (ReportType, Long?, Long?) -> ReportGenerator.Built = generator::build,
) : ReportStore, MigrationTarget {
    private val lock = Any()
    private var counter = 0

    init {
        if (migrate) FlywayMigrator.migrate(dataSource, "classpath:db/migration/report")
        counter = resumeCounter()
    }

    override suspend fun generate(req: GenerateReportRequest): ReportSnapshot {
        val built = buildFn(req.type, req.since, req.until) // reads sources OUTSIDE the lock/tx
        val snap = synchronized(lock) {
            ReportSnapshot(
                id = "rep-${counter++}",
                type = req.type,
                generatedAt = clock(),
                projectId = projectId,
                sources = built.sources,
                window = built.window,
                sections = built.sections,
            )
        }
        withContext(io) { tx { c -> insert(c, snap) } }
        return snap
    }

    override fun list(): List<ReportMeta> = reportMetas(allSnapshots())

    override fun get(id: String): ReportSnapshot = tx { c ->
        c.prepareStatement("SELECT snapshot_json FROM report WHERE id = ?").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs -> if (rs.next()) decode(rs.getString(1)) else null }
        }
    } ?: throw NotFoundException("report '$id' not found", code = "report_not_found")

    // ---- MigrationTarget (one row = the canonical CommJson(ReportSnapshot); the id carries the counter axis) ----

    override fun exportRows(): List<ByteArray> = tx { c ->
        c.prepareStatement("SELECT snapshot_json FROM report ORDER BY generated_at, id").use { st ->
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(MigrationRowCodec.encode(listOf(rs.getString(1)))) } }
        }
    }

    override fun importRows(rows: List<ByteArray>) {
        val imported = rows.map { decode(MigrationRowCodec.decode(it).first()) }
        tx { c ->
            c.prepareStatement("DELETE FROM report").use { it.executeUpdate() }
            for (snap in imported) insert(c, snap)
        }
        // Realign the id counter above the imported history, else the next generate reuses a rep-N already present
        // (PK collision / a lower id than the history). Mirrors PgEventSink.importRows resumeAtLeast / setval.
        synchronized(lock) { counter = maxOf(counter, (imported.mapNotNull { reportIdNum(it.id) }.maxOrNull()?.plus(1)) ?: 0) }
    }

    private fun insert(c: Connection, snap: ReportSnapshot) {
        c.prepareStatement("INSERT INTO report (id, project_id, generated_at, snapshot_json) VALUES (?, ?, ?, ?)").use {
            it.setString(1, snap.id); it.setString(2, snap.projectId); it.setLong(3, snap.generatedAt); it.setString(4, CommJson.encodeToString(ReportSnapshot.serializer(), snap)); it.executeUpdate()
        }
    }

    private fun allSnapshots(): List<ReportSnapshot> = tx { c ->
        c.prepareStatement("SELECT snapshot_json FROM report").use { st ->
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(decode(rs.getString(1))) } }
        }
    }

    private fun resumeCounter(): Int = tx { c ->
        c.prepareStatement("SELECT id FROM report").use { st ->
            st.executeQuery().use { rs ->
                var max = -1
                while (rs.next()) reportIdNum(rs.getString(1))?.let { if (it > max) max = it }
                max + 1
            }
        }
    }

    private fun decode(json: String): ReportSnapshot = CommJson.decodeFromString(ReportSnapshot.serializer(), json)

    private fun <T> tx(block: (Connection) -> T): T = dataSource.connection.use { c ->
        val prev = c.autoCommit
        c.autoCommit = false
        try {
            val r = block(c); c.commit(); r
        } catch (e: Throwable) {
            runCatching { c.rollback() }; throw e
        } finally {
            runCatching { c.autoCommit = prev }
        }
    }
}
