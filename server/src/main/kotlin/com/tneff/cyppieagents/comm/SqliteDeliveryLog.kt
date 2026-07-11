package com.tneff.cyppieagents.comm

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * CYP-415 (S-F / D2) — embedded-SQLite [DeliveryLog]. The delivered-id set lives in a composite-PK table
 * (`markDelivered` = `INSERT … ON CONFLICT DO NOTHING`, set-idempotent), replacing the newline-file impl.
 * Local `Sqlite*` sibling behind the store seam (Pg = dark cloud strand). Mirrors the reference Sqlite stores:
 * one held connection + WAL, `synchronized` (the interface is non-`suspend`).
 */
class SqliteDeliveryLog(dbPath: Path) : DeliveryLog, AutoCloseable {
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

    override fun isDelivered(projectId: String, agentId: String, messageId: String): Boolean = synchronized(lock) {
        conn.prepareStatement(
            "SELECT 1 FROM delivery WHERE project_id=? AND agent_id=? AND message_id=? LIMIT 1",
        ).use { ps ->
            ps.setString(1, projectId); ps.setString(2, agentId); ps.setString(3, messageId)
            ps.executeQuery().use { it.next() }
        }
    }

    override fun markDelivered(projectId: String, agentId: String, messageId: String) = synchronized(lock) {
        conn.prepareStatement(
            "INSERT INTO delivery(project_id, agent_id, message_id) VALUES(?, ?, ?) ON CONFLICT DO NOTHING",
        ).use { ps ->
            ps.setString(1, projectId); ps.setString(2, agentId); ps.setString(3, messageId)
            ps.executeUpdate()
        }
        Unit
    }

    override fun close() {
        runCatching { conn.close() }
    }

    private companion object {
        val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS delivery (
              project_id TEXT NOT NULL,
              agent_id TEXT NOT NULL,
              message_id TEXT NOT NULL,
              PRIMARY KEY (project_id, agent_id, message_id)
            )
        """.trimIndent()
    }
}
