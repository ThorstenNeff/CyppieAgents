package com.tneff.cyppieagents.agentevents

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.StoredAgentEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import org.slf4j.LoggerFactory

/**
 * CYP-198 — the durable [AgentEventStore]: the per-agent stream-json transcript on the established SQLite
 * line (mirrors [com.tneff.cyppieagents.events.SqliteEventSink]: xerial jdbc, WAL, one [Connection] under a
 * [mutex], a live [MutableSharedFlow] fan-out). `seq` is `INTEGER PRIMARY KEY AUTOINCREMENT` → gapless,
 * monotonic, never-reused even after retention deletes, and continues across a restart (survival). Events
 * are stored ALREADY masked (Gate #3). LOSSLESS within [retainPerAgent] (the last N events per agent).
 */
class SqliteAgentEventStore(
    dbPath: Path,
    private val retainPerAgent: Int = InMemoryAgentEventStore.DEFAULT_RETAIN_PER_AGENT,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AgentEventStore, AutoCloseable {
    private val log = LoggerFactory.getLogger("agentevents.sqlite")
    private val mutex = Mutex()
    private val conn: Connection
    private val live = MutableSharedFlow<StoredAgentEvent>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        dbPath.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.autoCommit = true
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute("PRAGMA busy_timeout=5000")
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS agent_events (" +
                    "seq INTEGER PRIMARY KEY AUTOINCREMENT, agent_id TEXT NOT NULL, project_id TEXT NOT NULL, " +
                    "ts INTEGER NOT NULL, event_json TEXT NOT NULL)",
            )
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_agent_seq ON agent_events(agent_id, seq)")
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_agentevents_project ON agent_events(project_id)")
        }
    }

    override suspend fun append(agentId: String, projectId: String, tsMs: Long, event: StreamJsonEvent): StoredAgentEvent {
        val json = CommJson.encodeToString(StreamJsonEvent.serializer(), event)
        val stored = withContext(io) {
            mutex.withLock {
                val seq = conn.prepareStatement(
                    "INSERT INTO agent_events(agent_id, project_id, ts, event_json) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS,
                ).use { ps ->
                    ps.setString(1, agentId); ps.setString(2, projectId); ps.setLong(3, tsMs); ps.setString(4, json)
                    ps.executeUpdate()
                    ps.generatedKeys.use { rs -> rs.next(); rs.getLong(1) }
                }
                // Retention: keep the last [retainPerAgent] rows for this agent (lossless within the window).
                if (retainPerAgent > 0) {
                    conn.prepareStatement(
                        "DELETE FROM agent_events WHERE agent_id=? AND seq NOT IN " +
                            "(SELECT seq FROM agent_events WHERE agent_id=? ORDER BY seq DESC LIMIT ?)",
                    ).use { ps -> ps.setString(1, agentId); ps.setString(2, agentId); ps.setInt(3, retainPerAgent); ps.executeUpdate() }
                }
                StoredAgentEvent(seq, agentId, projectId, tsMs, event)
            }
        }
        live.tryEmit(stored)
        return stored
    }

    override fun subscribe(agentId: String, sinceSeq: Long?): Flow<StoredAgentEvent> = flow {
        coroutineScope {
            // Attach to live BEFORE the replay query (no gap); drain with a seq>cursor guard (no dup).
            val buffered = Channel<StoredAgentEvent>(Channel.UNLIMITED)
            val job = launch { live.collect { if (it.agentId == agentId) buffered.send(it) } }
            var cursor = sinceSeq ?: 0L
            query(agentId, cursor, Int.MAX_VALUE).forEach { emit(it); cursor = maxOf(cursor, it.seq) }
            try {
                for (e in buffered) if (e.seq > cursor) { emit(e); cursor = e.seq }
            } finally {
                job.cancel()
            }
        }
    }

    override suspend fun query(agentId: String, sinceSeq: Long?, limit: Int): List<StoredAgentEvent> = withContext(io) {
        mutex.withLock {
            conn.prepareStatement(
                "SELECT seq, agent_id, project_id, ts, event_json FROM agent_events WHERE agent_id=? AND seq>? ORDER BY seq ASC LIMIT ?",
            ).use { ps ->
                ps.setString(1, agentId); ps.setLong(2, sinceSeq ?: 0L); ps.setInt(3, limit.coerceIn(0, Int.MAX_VALUE))
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val ev = runCatching { CommJson.decodeFromString(StreamJsonEvent.serializer(), rs.getString(5)) }.getOrNull()
                            if (ev != null) add(StoredAgentEvent(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), ev))
                        }
                    }
                }
            }
        }
    }

    override suspend fun deleteByProject(projectId: String): Int = withContext(io) {
        mutex.withLock {
            conn.prepareStatement("DELETE FROM agent_events WHERE project_id=?").use { ps ->
                ps.setString(1, projectId); ps.executeUpdate()
            }
        }
    }

    override fun close() { conn.close() }
}
