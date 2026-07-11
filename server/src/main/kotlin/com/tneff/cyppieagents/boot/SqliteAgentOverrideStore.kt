package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AgentAvatar
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import org.slf4j.LoggerFactory

/**
 * CYP-415 (S-F / D2) — embedded-SQLite [AgentOverrideStore] (one row per `(projectId, agentId)`, whole
 * [AgentOverride] as `override_json` so the polymorphic avatar ref survives). Local `Sqlite*` sibling behind the
 * store seam; single held connection + WAL, `synchronized` (non-`suspend` interface). Reuses [mergeStringFields]
 * (blank/null PRESERVES) so File/Sqlite/Pg cannot drift.
 *
 * **First-boot import (preserve-safe, [[default-agents-no-reset]]):** if [legacyJson] exists AND the table is
 * empty, the old `agent-overrides.json` is imported ONCE — so a deploy's operator customizations (names, the
 * dev2 `#B5419A` color, avatar refs) are NOT dropped by the File→SQLite switch. Deterministic + idempotent (the
 * empty-table guard skips it on every boot after the first).
 */
class SqliteAgentOverrideStore(dbPath: Path, legacyJson: Path? = null) : AgentOverrideStore, AutoCloseable {
    private val log = LoggerFactory.getLogger("boot.agentoverrides.sqlite")
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
        conn.prepareStatement("SELECT 1 FROM agent_override LIMIT 1").use { it.executeQuery().use { rs -> !rs.next() } }

    private fun importLegacy(legacyJson: Path) {
        runCatching {
            val decoded = CommJson.decodeFromString<Map<String, Map<String, AgentOverride>>>(Files.readString(legacyJson))
            var n = 0
            for ((pid, m) in decoded) for ((agentId, ov) in m) { upsert(pid, agentId, ov); n++ }
            log.info("imported {} legacy agent-override rows from {}", n, legacyJson)
        }.onFailure { log.warn("agent-override legacy import failed ({}) — starting empty", it.message) }
    }

    override fun overrideOf(projectId: String, agentId: String): AgentOverride? =
        synchronized(lock) { read(projectId, agentId) }

    override fun allFor(projectId: String): Map<String, AgentOverride> = synchronized(lock) {
        conn.prepareStatement("SELECT agent_id, override_json FROM agent_override WHERE project_id=?").use { ps ->
            ps.setString(1, projectId)
            ps.executeQuery().use { rs ->
                val out = LinkedHashMap<String, AgentOverride>()
                while (rs.next()) out[rs.getString(1)] = CommJson.decodeFromString(AgentOverride.serializer(), rs.getString(2))
                out
            }
        }
    }

    override fun put(projectId: String, agentId: String, name: String?, color: String?, persona: String?, launch: String?): AgentOverride =
        synchronized(lock) {
            val next = (read(projectId, agentId) ?: AgentOverride()).mergeStringFields(name, color, persona, launch)
            upsert(projectId, agentId, next)
            next
        }

    override fun setAvatar(projectId: String, agentId: String, avatar: AgentAvatar?): AgentOverride =
        synchronized(lock) {
            val next = (read(projectId, agentId) ?: AgentOverride()).copy(avatar = avatar)
            upsert(projectId, agentId, next)
            next
        }

    override fun removeAgent(projectId: String, agentId: String): Boolean = synchronized(lock) {
        conn.prepareStatement("DELETE FROM agent_override WHERE project_id=? AND agent_id=?").use { ps ->
            ps.setString(1, projectId); ps.setString(2, agentId); ps.executeUpdate() > 0
        }
    }

    override fun removeProject(projectId: String): Int = synchronized(lock) {
        if (projectId.isBlank()) return@synchronized 0 // fail-closed: never an unscoped clear
        conn.prepareStatement("DELETE FROM agent_override WHERE project_id=?").use { ps ->
            ps.setString(1, projectId); ps.executeUpdate()
        }
    }

    /** Read one override (caller holds [lock]). */
    private fun read(projectId: String, agentId: String): AgentOverride? =
        conn.prepareStatement("SELECT override_json FROM agent_override WHERE project_id=? AND agent_id=?").use { ps ->
            ps.setString(1, projectId); ps.setString(2, agentId)
            ps.executeQuery().use { rs -> if (rs.next()) CommJson.decodeFromString(AgentOverride.serializer(), rs.getString(1)) else null }
        }

    /** Upsert one override (caller holds [lock], or init before serving). */
    private fun upsert(projectId: String, agentId: String, ov: AgentOverride) {
        conn.prepareStatement(
            "INSERT INTO agent_override(project_id, agent_id, override_json) VALUES(?, ?, ?) " +
                "ON CONFLICT(project_id, agent_id) DO UPDATE SET override_json=excluded.override_json",
        ).use { ps ->
            ps.setString(1, projectId); ps.setString(2, agentId)
            ps.setString(3, CommJson.encodeToString(AgentOverride.serializer(), ov))
            ps.executeUpdate()
        }
    }

    override fun close() {
        runCatching { conn.close() }
    }

    private companion object {
        val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS agent_override (
              project_id TEXT NOT NULL,
              agent_id TEXT NOT NULL,
              override_json TEXT NOT NULL,
              PRIMARY KEY (project_id, agent_id)
            )
        """.trimIndent()
    }
}
