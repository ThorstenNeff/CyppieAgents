package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.CompactConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * CYP-415 (S-F / D2) — embedded-SQLite [CompactConfigStore] (one row per project, whole [CompactConfig] as
 * `config_json`). Unset project → the fail-closed default `CompactConfig()` (`allowed=false`). Local `Sqlite*`
 * sibling; single held connection + WAL, `synchronized` (non-`suspend` interface).
 */
class SqliteCompactConfigStore(dbPath: Path) : CompactConfigStore, AutoCloseable {
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

    override fun get(projectId: String): CompactConfig = synchronized(lock) {
        conn.prepareStatement("SELECT config_json FROM compact_config WHERE project_id=?").use { ps ->
            ps.setString(1, projectId)
            ps.executeQuery().use { rs ->
                if (rs.next()) CommJson.decodeFromString(CompactConfig.serializer(), rs.getString(1))
                else CompactConfig() // fail-closed default (allowed=false)
            }
        }
    }

    override fun set(projectId: String, config: CompactConfig) = synchronized(lock) {
        conn.prepareStatement(
            "INSERT INTO compact_config(project_id, config_json) VALUES(?, ?) " +
                "ON CONFLICT(project_id) DO UPDATE SET config_json=excluded.config_json",
        ).use { ps ->
            ps.setString(1, projectId)
            ps.setString(2, CommJson.encodeToString(CompactConfig.serializer(), config))
            ps.executeUpdate()
        }
        Unit
    }

    override fun close() {
        runCatching { conn.close() }
    }

    private companion object {
        val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS compact_config (
              project_id TEXT PRIMARY KEY,
              config_json TEXT NOT NULL
            )
        """.trimIndent()
    }
}
