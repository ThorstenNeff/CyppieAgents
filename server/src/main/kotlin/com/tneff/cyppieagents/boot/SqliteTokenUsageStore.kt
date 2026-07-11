package com.tneff.cyppieagents.boot

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * CYP-415 (S-F / D2) — embedded-SQLite [TokenUsageStore] (one row per `(projectId, agentId)`). `put(null)`
 * DELETEs (unknown is never stored — the null≠0 honesty of the feed); `put(value)` upserts. Local `Sqlite*`
 * sibling behind the store seam; single held connection + WAL, `synchronized` (non-`suspend` interface).
 */
class SqliteTokenUsageStore(dbPath: Path) : TokenUsageStore, AutoCloseable {
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

    override fun allFor(projectId: String): Map<String, Int> = synchronized(lock) {
        conn.prepareStatement("SELECT agent_id, context_tokens FROM token_usage WHERE project_id=?").use { ps ->
            ps.setString(1, projectId)
            ps.executeQuery().use { rs ->
                val out = LinkedHashMap<String, Int>()
                while (rs.next()) out[rs.getString(1)] = rs.getInt(2)
                out
            }
        }
    }

    override fun put(projectId: String, agentId: String, contextTokens: Int?) = synchronized(lock) {
        if (contextTokens == null) {
            conn.prepareStatement("DELETE FROM token_usage WHERE project_id=? AND agent_id=?").use { ps ->
                ps.setString(1, projectId); ps.setString(2, agentId); ps.executeUpdate()
            }
        } else {
            conn.prepareStatement(
                "INSERT INTO token_usage(project_id, agent_id, context_tokens) VALUES(?, ?, ?) " +
                    "ON CONFLICT(project_id, agent_id) DO UPDATE SET context_tokens=excluded.context_tokens",
            ).use { ps ->
                ps.setString(1, projectId); ps.setString(2, agentId); ps.setInt(3, contextTokens); ps.executeUpdate()
            }
        }
        Unit
    }

    override fun removeAgent(projectId: String, agentId: String) = synchronized(lock) {
        conn.prepareStatement("DELETE FROM token_usage WHERE project_id=? AND agent_id=?").use { ps ->
            ps.setString(1, projectId); ps.setString(2, agentId); ps.executeUpdate()
        }
        Unit
    }

    override fun removeProject(projectId: String): Int = synchronized(lock) {
        conn.prepareStatement("DELETE FROM token_usage WHERE project_id=?").use { ps ->
            ps.setString(1, projectId); ps.executeUpdate()
        }
    }

    override fun close() {
        runCatching { conn.close() }
    }

    private companion object {
        val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS token_usage (
              project_id TEXT NOT NULL,
              agent_id TEXT NOT NULL,
              context_tokens INTEGER NOT NULL,
              PRIMARY KEY (project_id, agent_id)
            )
        """.trimIndent()
    }
}
