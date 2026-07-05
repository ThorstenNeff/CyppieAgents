package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.db.FlywayMigrator
import com.tneff.cyppieagents.db.MigrationRowCodec
import com.tneff.cyppieagents.db.MigrationTarget
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventPage
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import java.security.SecureRandom
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.random.RandomGenerator
import javax.sql.DataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * CYP-220 Phase 6 S5 — the **Postgres** [EventSink]. Behaviour-identical to
 * [SqliteEventSink]: `seq` is stamped by the injected [TimeSource] **under the [mutex]** (append-only, total
 * order owned in one place, never DB-derived), resumed above the persisted `MAX(seq)` on open (restart never
 * reuses `seq`); batch inserts in one tx; cursor pagination over `seq ASC`; the SAME [EventFilter] axes in SQL;
 * a live in-process [MutableSharedFlow] fan-out ([subscribe] is process-local — the DB is durability only);
 * fail-closed `deleteByProject` (blank → 0). **Append-only ⇒ no read-modify-write ⇒ RMW-immune** (no
 * `FOR UPDATE` needed, unlike S4). High-volume, so the tx-batched insert + the `(seq)`/`(agent_id, seq)`
 * indexes carry the write/pagination path.
 *
 * Concurrency: the [mutex] serialises stamping + the emit so `seq` is gapless + totally ordered regardless of
 * which coroutine appends; JDBC runs on [io]. Reads (query) are lock-free (Postgres MVCC).
 */
class PgEventSink(
    private val dataSource: DataSource,
    private val time: TimeSource,
    private val rnd: RandomGenerator = SecureRandom(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    migrate: Boolean = true,
) : EventSink, MigrationTarget, AutoCloseable {
    private val log = LoggerFactory.getLogger("events.sink.pg")
    private val mutex = Mutex()
    private val stream = MutableSharedFlow<Event>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        if (migrate) FlywayMigrator.migrate(dataSource, "classpath:db/migration/eventlog")
        // Resume seq above the highest persisted value so a restart can't reuse seq numbers (parity w/ sqlite).
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT COALESCE(MAX(seq), 0) AS m FROM events").use { st ->
                st.executeQuery().use { rs -> if (rs.next()) time.resumeAtLeast(rs.getLong("m")) }
            }
        }
    }

    override suspend fun appendBatch(drafts: List<EventDraft>): List<Event> {
        if (drafts.isEmpty()) return emptyList()
        val stamped = withContext(io) {
            mutex.withLock {
                val out = drafts.map { time.stamp(it, rnd) } // seq assigned under lock → ordered
                tx { c ->
                    c.prepareStatement(INSERT_SQL).use { ps ->
                        for (e in out) { bind(ps, e); ps.addBatch() }
                        ps.executeBatch()
                    }
                }
                out
            }
        }
        stamped.forEach { stream.tryEmit(it) }
        return stamped
    }

    override suspend fun query(filter: EventFilter, page: Page): EventPage = withContext(io) {
        val wheres = ArrayList<String>()
        val binds = ArrayList<(PreparedStatement, Int) -> Unit>()
        page.afterSeq?.let { v -> wheres += "seq > ?"; binds += { ps, i -> ps.setLong(i, v) } }
        filter.agentId?.let { v -> wheres += "agent_id = ?"; binds += { ps, i -> ps.setString(i, v) } }
        filter.type?.let { v -> wheres += "type = ?"; binds += { ps, i -> ps.setString(i, v.wire) } }
        filter.severity?.let { v -> wheres += "severity = ?"; binds += { ps, i -> ps.setString(i, v.name) } }
        filter.since?.let { v -> wheres += "ts >= ?"; binds += { ps, i -> ps.setLong(i, v) } }
        filter.until?.let { v -> wheres += "ts < ?"; binds += { ps, i -> ps.setLong(i, v) } }
        filter.correlationId?.let { v -> wheres += "correlation_id = ?"; binds += { ps, i -> ps.setString(i, v) } }
        filter.sessionId?.let { v -> wheres += "session_id = ?"; binds += { ps, i -> ps.setString(i, v) } }
        filter.projectId?.let { v -> wheres += "project_id = ?"; binds += { ps, i -> ps.setString(i, v) } }

        val where = if (wheres.isEmpty()) "" else "WHERE " + wheres.joinToString(" AND ")
        val sql = "SELECT $COLUMNS FROM events $where ORDER BY seq ASC LIMIT ?"
        dataSource.connection.use { c ->
            c.prepareStatement(sql).use { ps ->
                var idx = 1
                binds.forEach { it(ps, idx++) }
                ps.setInt(idx, page.limit)
                ps.executeQuery().use { rs ->
                    val list = ArrayList<Event>()
                    while (rs.next()) list.add(readRow(rs))
                    val next = if (page.limit > 0 && list.size >= page.limit) list.last().seq else null
                    EventPage(list, next)
                }
            }
        }
    }

    override fun subscribe(filter: EventFilter): Flow<Event> = stream.asSharedFlow().filter(filter::matches)

    override suspend fun deleteByProject(projectId: String): Int = withContext(io) {
        if (projectId.isBlank()) return@withContext 0 // fail-closed: never an unscoped wipe
        tx { c ->
            c.prepareStatement("DELETE FROM events WHERE project_id = ?").use { ps -> ps.setString(1, projectId); ps.executeUpdate() }
        }
    }

    // ---- MigrationTarget (one row = the canonical CommJson(Event); preserves every nullable field) ----

    override fun exportRows(): List<ByteArray> = dataSource.connection.use { c ->
        c.prepareStatement("SELECT $COLUMNS FROM events ORDER BY seq ASC").use { st ->
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(MigrationRowCodec.encode(listOf(CommJson.encodeToString(Event.serializer(), readRow(rs))))) }
            }
        }
    }

    override fun importRows(rows: List<ByteArray>) {
        tx { c ->
            c.prepareStatement("DELETE FROM events").use { it.executeUpdate() }
            c.prepareStatement(INSERT_SQL).use { ps ->
                for (row in rows) {
                    val e = CommJson.decodeFromString(Event.serializer(), MigrationRowCodec.decode(row).first())
                    bind(ps, e); ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    override fun close() {}

    private fun bind(ps: PreparedStatement, e: Event) {
        val sourceTs = e.sourceTs
        val sessionId = e.sessionId
        val correlationId = e.correlationId
        ps.setLong(1, e.seq)
        ps.setString(2, e.id)
        ps.setLong(3, e.ts)
        if (sourceTs == null) ps.setNull(4, Types.BIGINT) else ps.setLong(4, sourceTs)
        ps.setString(5, e.agentId)
        ps.setString(6, e.projectId)
        if (sessionId == null) ps.setNull(7, Types.VARCHAR) else ps.setString(7, sessionId)
        if (correlationId == null) ps.setNull(8, Types.VARCHAR) else ps.setString(8, correlationId)
        ps.setString(9, e.type.wire)
        ps.setString(10, e.severity.name)
        ps.setString(11, CommJson.encodeToString(JsonObject.serializer(), e.detail))
    }

    private fun readRow(rs: ResultSet): Event {
        val sourceTs = rs.getLong("source_ts").let { if (rs.wasNull()) null else it }
        val detail = CommJson.decodeFromString(JsonObject.serializer(), rs.getString("detail"))
        return Event(
            id = rs.getString("id"),
            ts = rs.getLong("ts"),
            seq = rs.getLong("seq"),
            sourceTs = sourceTs,
            agentId = rs.getString("agent_id"),
            projectId = rs.getString("project_id"),
            sessionId = rs.getString("session_id"),
            correlationId = rs.getString("correlation_id"),
            type = EventType.fromWire(rs.getString("type")),
            severity = runCatching { Severity.valueOf(rs.getString("severity")) }.getOrDefault(Severity.INFO),
            detail = detail,
        )
    }

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

    private companion object {
        const val COLUMNS =
            "seq, id, ts, source_ts, agent_id, project_id, session_id, correlation_id, type, severity, detail"
        val INSERT_SQL = "INSERT INTO events ($COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    }
}
