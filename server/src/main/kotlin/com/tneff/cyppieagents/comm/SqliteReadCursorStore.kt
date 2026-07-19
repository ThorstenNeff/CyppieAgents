package com.tneff.cyppieagents.comm

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * CYP-705 — embedded-SQLite [ReadCursorStore]. One row per `(project_id, subject, channel_id)`; `markRead`
 * is an idempotent, **advance-only** upsert (`ON CONFLICT DO UPDATE SET last_read_seq = max(...)`), so a
 * lower/late `upToSeq` never regresses the cursor. Mirrors [SqliteDeliveryLog]: one held connection + WAL,
 * `synchronized` (the interface is non-`suspend`). Local `Sqlite*` sibling behind the store seam.
 */
class SqliteReadCursorStore(dbPath: Path) : ReadCursorStore, AutoCloseable {
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

    override fun lastReadSeq(projectId: String, subject: String, channelId: String): Long? = synchronized(lock) {
        conn.prepareStatement(
            "SELECT last_read_seq FROM read_cursor WHERE project_id=? AND subject=? AND channel_id=? LIMIT 1",
        ).use { ps ->
            ps.setString(1, projectId); ps.setString(2, subject); ps.setString(3, channelId)
            ps.executeQuery().use { if (it.next()) it.getLong(1) else null }
        }
    }

    override fun markRead(projectId: String, subject: String, channelId: String, upToSeq: Long) = synchronized(lock) {
        conn.prepareStatement(
            """
            INSERT INTO read_cursor(project_id, subject, channel_id, last_read_seq) VALUES(?, ?, ?, ?)
            ON CONFLICT(project_id, subject, channel_id)
            DO UPDATE SET last_read_seq = max(last_read_seq, excluded.last_read_seq)
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, projectId); ps.setString(2, subject); ps.setString(3, channelId); ps.setLong(4, upToSeq)
            ps.executeUpdate()
        }
        Unit
    }

    override fun close() {
        runCatching { conn.close() }
    }

    private companion object {
        val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS read_cursor (
              project_id TEXT NOT NULL,
              subject TEXT NOT NULL,
              channel_id TEXT NOT NULL,
              last_read_seq INTEGER NOT NULL,
              PRIMARY KEY (project_id, subject, channel_id)
            )
        """.trimIndent()
    }
}
