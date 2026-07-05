package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.db.FlywayMigrator
import com.tneff.cyppieagents.db.MigrationRowCodec
import com.tneff.cyppieagents.db.MigrationTarget
import com.tneff.cyppieagents.model.AgentAvatar
import java.sql.Connection
import javax.sql.DataSource

/**
 * CYP-220 Phase 6 S4 — the **Postgres** [AgentOverrideStore] impl. Behaviour-identical to
 * [FileAgentOverrideStore]: same per-`projectId` keying, the same **blank/null-preserves** merge (shared
 * [mergeStringFields], so File and Pg cannot drift), the same explicit avatar-clear path, and the same
 * fail-closed cascade (a blank `projectId` clears nothing). **Non-secret** (display name/colour + connector
 * persona/launch + the avatar override), so no [com.tneff.cyppieagents.crypto.SecretCipher] — the whole
 * [AgentOverride] is stored as its canonical [CommJson] form (one text column), which preserves the
 * polymorphic [AgentAvatar] exactly.
 *
 * One row per `(project_id, agent_id)`; `project_id` is a column (multi-project), so a project cascade-delete
 * scopes to exactly its rows.
 */
class PgAgentOverrideStore(
    private val dataSource: DataSource,
    migrate: Boolean = true,
) : AgentOverrideStore, MigrationTarget {

    init { if (migrate) FlywayMigrator.migrate(dataSource, "classpath:db/migration/agentoverride") }

    override fun overrideOf(projectId: String, agentId: String): AgentOverride? = tx { c ->
        c.prepareStatement("SELECT override_json FROM agent_override WHERE project_id = ? AND agent_id = ?").use { st ->
            st.setString(1, projectId); st.setString(2, agentId)
            st.executeQuery().use { rs -> if (rs.next()) decode(rs.getString(1)) else null }
        }
    }

    override fun allFor(projectId: String): Map<String, AgentOverride> = tx { c ->
        c.prepareStatement("SELECT agent_id, override_json FROM agent_override WHERE project_id = ?").use { st ->
            st.setString(1, projectId)
            st.executeQuery().use { rs ->
                buildMap { while (rs.next()) put(rs.getString(1), decode(rs.getString(2))) }
            }
        }
    }

    override fun put(projectId: String, agentId: String, name: String?, color: String?, persona: String?, launch: String?): AgentOverride {
        val next = (overrideOf(projectId, agentId) ?: AgentOverride()).mergeStringFields(name, color, persona, launch)
        upsert(projectId, agentId, next)
        return next
    }

    override fun setAvatar(projectId: String, agentId: String, avatar: AgentAvatar?): AgentOverride {
        val next = (overrideOf(projectId, agentId) ?: AgentOverride()).copy(avatar = avatar)
        upsert(projectId, agentId, next)
        return next
    }

    override fun removeAgent(projectId: String, agentId: String): Boolean = tx { c ->
        c.prepareStatement("DELETE FROM agent_override WHERE project_id = ? AND agent_id = ?")
            .use { it.setString(1, projectId); it.setString(2, agentId); it.executeUpdate() } > 0
    }

    override fun removeProject(projectId: String): Int {
        if (projectId.isBlank()) return 0 // fail-closed: never an unscoped clear
        return tx { c ->
            c.prepareStatement("DELETE FROM agent_override WHERE project_id = ?")
                .use { it.setString(1, projectId); it.executeUpdate() }
        }
    }

    // ---- MigrationTarget (canonical rows: [project_id, agent_id, override_json]) ----

    override fun exportRows(): List<ByteArray> = tx { c ->
        c.prepareStatement("SELECT project_id, agent_id, override_json FROM agent_override ORDER BY project_id, agent_id").use { st ->
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(MigrationRowCodec.encode(listOf(rs.getString(1), rs.getString(2), rs.getString(3)))) }
            }
        }
    }

    override fun importRows(rows: List<ByteArray>) = tx { c ->
        c.prepareStatement("DELETE FROM agent_override").use { it.executeUpdate() }
        for (row in rows) {
            val f = MigrationRowCodec.decode(row) // [projectId, agentId, overrideJson]
            c.prepareStatement("INSERT INTO agent_override (project_id, agent_id, override_json) VALUES (?, ?, ?)")
                .use { it.setString(1, f[0]); it.setString(2, f[1]); it.setString(3, f[2]); it.executeUpdate() }
        }
        Unit
    }

    private fun upsert(projectId: String, agentId: String, o: AgentOverride) = tx { c ->
        c.prepareStatement(
            "INSERT INTO agent_override (project_id, agent_id, override_json) VALUES (?, ?, ?) " +
                "ON CONFLICT (project_id, agent_id) DO UPDATE SET override_json = EXCLUDED.override_json",
        ).use { it.setString(1, projectId); it.setString(2, agentId); it.setString(3, CommJson.encodeToString(o)); it.executeUpdate() }
        Unit
    }

    private fun decode(json: String): AgentOverride = CommJson.decodeFromString(json)

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
}
