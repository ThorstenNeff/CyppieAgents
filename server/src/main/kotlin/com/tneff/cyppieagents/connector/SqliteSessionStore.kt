package com.tneff.cyppieagents.connector

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * CYP-415 (S-F / D2) — embedded-SQLite [SessionStore] (one row per `(projectId, agentId)`). The createdAt-preserve
 * rule (keep `created_at` only while the SAME session id is rewritten; a NEW id resets it to `now`) is a single
 * `INSERT … ON CONFLICT … DO UPDATE` with a CASE — atomic, RMW-free. Benign switch (session ids re-derive), no
 * first-boot import. Single held connection + WAL, `synchronized` (non-`suspend` interface).
 */
class SqliteSessionStore(dbPath: Path) : SessionStore, AutoCloseable {
    private val lock = Any()
    private val conn: Connection

    init {
        dbPath.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.autoCommit = true
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute("PRAGMA busy_timeout=5000")
            st.executeUpdate(CREATE_TABLE)
        }
    }

    override fun find(projectId: String, agentId: String): SessionEntry? = synchronized(lock) {
        conn.prepareStatement(
            "SELECT session_id, created_at, last_activity FROM session WHERE project_id=? AND agent_id=?",
        ).use { ps ->
            ps.setString(1, projectId); ps.setString(2, agentId)
            ps.executeQuery().use { rs ->
                if (rs.next()) SessionEntry(projectId, agentId, rs.getString(1), rs.getLong(2), rs.getLong(3)) else null
            }
        }
    }

    override fun upsert(projectId: String, agentId: String, sessionId: String, now: Long) = synchronized(lock) {
        // created_at is kept iff the SAME session id is rewritten (a lastActivity refresh); a NEW id → reset to now.
        // In the DO UPDATE clause, `session.col` = the existing row, `excluded.col` = the proposed new value.
        conn.prepareStatement(
            "INSERT INTO session(project_id, agent_id, session_id, created_at, last_activity) VALUES(?, ?, ?, ?, ?) " +
                "ON CONFLICT(project_id, agent_id) DO UPDATE SET " +
                "session_id=excluded.session_id, last_activity=excluded.last_activity, " +
                "created_at=CASE WHEN session.session_id=excluded.session_id THEN session.created_at ELSE excluded.created_at END",
        ).use { ps ->
            ps.setString(1, projectId); ps.setString(2, agentId); ps.setString(3, sessionId)
            ps.setLong(4, now); ps.setLong(5, now)
            ps.executeUpdate()
        }
        Unit
    }

    override fun clear(projectId: String, agentId: String) = synchronized(lock) {
        conn.prepareStatement("DELETE FROM session WHERE project_id=? AND agent_id=?").use { ps ->
            ps.setString(1, projectId); ps.setString(2, agentId); ps.executeUpdate()
        }
        Unit
    }

    override fun close() {
        runCatching { conn.close() }
    }

    private companion object {
        val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS session (
              project_id TEXT NOT NULL,
              agent_id TEXT NOT NULL,
              session_id TEXT NOT NULL,
              created_at INTEGER NOT NULL,
              last_activity INTEGER NOT NULL,
              PRIMARY KEY (project_id, agent_id)
            )
        """.trimIndent()
    }
}
