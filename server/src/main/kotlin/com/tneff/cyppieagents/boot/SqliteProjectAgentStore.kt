package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.CommJson
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import org.slf4j.LoggerFactory

/**
 * CYP-415 (S-F / D2) — embedded-SQLite [ProjectAgentStore] (one row per `(projectId, agentId)`, whole
 * [StoredAgent] as `agent_json`; `agentsFor` sorted by id). [put] never swallows a write failure — a JDBC error
 * propagates (CR3). First-boot import of the old `project-agents.json` (empty-table guard) preserves an operator
 * deploy's runtime-added agents. Single held connection + WAL, `synchronized` (non-`suspend` interface).
 */
class SqliteProjectAgentStore(dbPath: Path, legacyJson: Path? = null) : ProjectAgentStore, AutoCloseable {
    private val log = LoggerFactory.getLogger("boot.projectagents.sqlite")
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
        if (legacyJson != null && Files.exists(legacyJson) && isEmpty()) importLegacy(legacyJson)
    }

    private fun isEmpty(): Boolean =
        conn.prepareStatement("SELECT 1 FROM project_agent LIMIT 1").use { it.executeQuery().use { rs -> !rs.next() } }

    private fun importLegacy(legacyJson: Path) {
        runCatching {
            val decoded = CommJson.decodeFromString<Map<String, Map<String, StoredAgent>>>(Files.readString(legacyJson))
            var n = 0
            for ((pid, m) in decoded) for ((_, agent) in m) { put(pid, agent); n++ }
            log.info("imported {} legacy project-agent rows from {}", n, legacyJson)
        }.onFailure { log.warn("project-agent legacy import failed ({}) — starting empty", it.message) }
    }

    override fun agentsFor(projectId: String): List<StoredAgent> = synchronized(lock) {
        conn.prepareStatement("SELECT agent_json FROM project_agent WHERE project_id=? ORDER BY agent_id").use { ps ->
            ps.setString(1, projectId)
            ps.executeQuery().use { rs ->
                val out = ArrayList<StoredAgent>()
                while (rs.next()) out.add(CommJson.decodeFromString(StoredAgent.serializer(), rs.getString(1)))
                out
            }
        }
    }

    override fun contains(projectId: String, agentId: String): Boolean = synchronized(lock) {
        conn.prepareStatement("SELECT 1 FROM project_agent WHERE project_id=? AND agent_id=? LIMIT 1").use { ps ->
            ps.setString(1, projectId); ps.setString(2, agentId)
            ps.executeQuery().use { it.next() }
        }
    }

    override fun put(projectId: String, agent: StoredAgent): Unit = synchronized(lock) {
        // No runCatching: a write failure MUST propagate (CR3 — the caller relies on durability).
        conn.prepareStatement(
            "INSERT INTO project_agent(project_id, agent_id, agent_json) VALUES(?, ?, ?) " +
                "ON CONFLICT(project_id, agent_id) DO UPDATE SET agent_json=excluded.agent_json",
        ).use { ps ->
            ps.setString(1, projectId); ps.setString(2, agent.id)
            ps.setString(3, CommJson.encodeToString(StoredAgent.serializer(), agent))
            ps.executeUpdate()
        }
    }

    override fun remove(projectId: String, agentId: String): Boolean = synchronized(lock) {
        conn.prepareStatement("DELETE FROM project_agent WHERE project_id=? AND agent_id=?").use { ps ->
            ps.setString(1, projectId); ps.setString(2, agentId); ps.executeUpdate() > 0
        }
    }

    override fun removeProject(projectId: String): Int = synchronized(lock) {
        conn.prepareStatement("DELETE FROM project_agent WHERE project_id=?").use { ps ->
            ps.setString(1, projectId); ps.executeUpdate()
        }
    }

    override fun close() {
        runCatching { conn.close() }
    }

    private companion object {
        val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS project_agent (
              project_id TEXT NOT NULL,
              agent_id TEXT NOT NULL,
              agent_json TEXT NOT NULL,
              PRIMARY KEY (project_id, agent_id)
            )
        """.trimIndent()
    }
}
