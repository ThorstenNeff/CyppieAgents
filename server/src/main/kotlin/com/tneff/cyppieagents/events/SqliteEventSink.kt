package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventPage
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.random.RandomGenerator
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
 * SQLite [EventSink] (PRD §3.2, MVP driver = xerial sqlite-jdbc, pinned). WAL on, batch inserts,
 * own `events` table separate from hub data. The SQL never leaks past this seam — swappable for
 * Redis Streams / Postgres later (Exposé §8) without touching callers; SQLDelight is the documented
 * upgrade path (02 §15), not MVP.
 *
 * Concurrency: a single JDBC [Connection] guarded by [mutex] — SQLite serializes writes anyway, and
 * the mutex keeps the connection from being touched concurrently. JDBC runs on [io] so it never
 * blocks a caller's dispatcher. Stamping happens under the lock, so `seq` is gapless + total-ordered
 * regardless of which coroutine appends.
 *
 * Restart durability: on open we resume the [TimeSource] above the highest persisted `seq`, so a
 * restart never reuses sequence numbers (total order survives — PRD §5).
 */
class SqliteEventSink(
    dbPath: Path,
    private val time: TimeSource,
    private val rnd: RandomGenerator = SecureRandom(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : EventSink, AutoCloseable {
    private val log = LoggerFactory.getLogger("events.sink.sqlite")
    private val mutex = Mutex()
    private val conn: Connection
    private val stream = MutableSharedFlow<Event>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        dbPath.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.autoCommit = true
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL") // decouples readers from the writer; survives restart
            st.execute("PRAGMA synchronous=NORMAL") // WAL-safe durability without fsync on every commit
            st.execute("PRAGMA busy_timeout=5000")
            st.executeUpdate(CREATE_TABLE)
            INDEXES.forEach { st.executeUpdate(it) }
        }
        // Resume seq above the highest persisted value so a restart can't reuse seq numbers.
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COALESCE(MAX(seq), 0) AS m FROM events").use { rs ->
                if (rs.next()) time.resumeAtLeast(rs.getLong("m"))
            }
        }
    }

    override suspend fun appendBatch(drafts: List<EventDraft>): List<Event> {
        if (drafts.isEmpty()) return emptyList()
        val stamped = withContext(io) {
            mutex.withLock {
                val out = drafts.map { time.stamp(it, rnd) } // seq assigned under lock → ordered
                conn.autoCommit = false
                try {
                    conn.prepareStatement(INSERT_SQL).use { ps ->
                        for (e in out) {
                            bind(ps, e)
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
                    conn.commit()
                } catch (e: Throwable) {
                    runCatching { conn.rollback() }
                    throw e
                } finally {
                    conn.autoCommit = true
                }
                out
            }
        }
        stamped.forEach { stream.tryEmit(it) }
        return stamped
    }

    override suspend fun query(filter: EventFilter, page: Page): EventPage = withContext(io) {
        mutex.withLock {
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

            val where = if (wheres.isEmpty()) "" else "WHERE " + wheres.joinToString(" AND ")
            val sql = "SELECT $COLUMNS FROM events $where ORDER BY seq ASC LIMIT ?"

            conn.prepareStatement(sql).use { ps ->
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

    override fun close() {
        runCatching { conn.close() }.onFailure { log.warn("error closing event sink", it) }
    }

    private fun bind(ps: PreparedStatement, e: Event) {
        // Locals: e.* are :core (different module) public API props — no cross-module smart-cast.
        val sourceTs = e.sourceTs
        val sessionId = e.sessionId
        val correlationId = e.correlationId
        ps.setLong(1, e.seq)
        ps.setString(2, e.id)
        ps.setLong(3, e.ts)
        if (sourceTs == null) ps.setNull(4, Types.INTEGER) else ps.setLong(4, sourceTs)
        ps.setString(5, e.agentId)
        ps.setString(6, e.teamId)
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
            teamId = rs.getString("team_id"),
            sessionId = rs.getString("session_id"),
            correlationId = rs.getString("correlation_id"),
            type = EventType.fromWire(rs.getString("type")),
            severity = runCatching { Severity.valueOf(rs.getString("severity")) }.getOrDefault(Severity.INFO),
            detail = detail,
        )
    }

    private companion object {
        const val COLUMNS =
            "seq, id, ts, source_ts, agent_id, team_id, session_id, correlation_id, type, severity, detail"

        // seq is the PRIMARY KEY → it IS the (seq) index used for total order + stable paging.
        val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS events (
              seq            INTEGER PRIMARY KEY,
              id             TEXT    NOT NULL,
              ts             INTEGER NOT NULL,
              source_ts      INTEGER,
              agent_id       TEXT    NOT NULL,
              team_id        TEXT    NOT NULL,
              session_id     TEXT,
              correlation_id TEXT,
              type           TEXT    NOT NULL,
              severity       TEXT    NOT NULL,
              detail         TEXT    NOT NULL
            )
        """.trimIndent()

        // Filter axes of the Browse UI (PRD §4 "Indizes").
        val INDEXES = listOf(
            "CREATE INDEX IF NOT EXISTS idx_events_agent_seq ON events(agent_id, seq)",
            "CREATE INDEX IF NOT EXISTS idx_events_type_seq ON events(type, seq)",
            "CREATE INDEX IF NOT EXISTS idx_events_corr ON events(correlation_id)",
            "CREATE INDEX IF NOT EXISTS idx_events_session ON events(session_id)",
            "CREATE INDEX IF NOT EXISTS idx_events_ts ON events(ts)",
        )

        val INSERT_SQL =
            "INSERT INTO events ($COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    }
}
