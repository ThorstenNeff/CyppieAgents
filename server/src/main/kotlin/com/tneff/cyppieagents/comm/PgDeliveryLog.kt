package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.db.FlywayMigrator
import com.tneff.cyppieagents.db.MigrationRowCodec
import com.tneff.cyppieagents.db.MigrationTarget
import java.sql.Connection
import javax.sql.DataSource

/**
 * CYP-220 Phase 6 S6 — the **Postgres** [DeliveryLog]. Behaviour-identical to [JsonFileDeliveryLog]: a durable
 * per-recipient **delivered-message-id set**, keyed `(project_id, agent_id, message_id)`. **NON-SECRET** (dedup
 * keys, no content). **APPEND-ONLY, set-add idempotent** — `markDelivered` is a single `INSERT … ON CONFLICT DO
 * NOTHING` (the set semantics live in the composite PK, not a Kotlin read-then-write) ⇒ **RMW-immune**, no
 * ordering counter ⇒ no import realign. Dedup over `message.id` (never a positional ordinal, CYP-132 R3), so a
 * reset MessageStore + a surviving log can never make delivery skip a NEW message.
 */
class PgDeliveryLog(
    private val dataSource: DataSource,
    migrate: Boolean = true,
) : DeliveryLog, MigrationTarget {

    init { if (migrate) FlywayMigrator.migrate(dataSource, "classpath:db/migration/delivery") }

    override fun isDelivered(projectId: String, agentId: String, messageId: String): Boolean = tx { c ->
        c.prepareStatement("SELECT 1 FROM delivery WHERE project_id = ? AND agent_id = ? AND message_id = ?").use { st ->
            st.setString(1, projectId); st.setString(2, agentId); st.setString(3, messageId)
            st.executeQuery().use { rs -> rs.next() }
        }
    }

    override fun markDelivered(projectId: String, agentId: String, messageId: String) {
        tx { c ->
            c.prepareStatement("INSERT INTO delivery (project_id, agent_id, message_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING").use {
                it.setString(1, projectId); it.setString(2, agentId); it.setString(3, messageId); it.executeUpdate()
            }
        }
    }

    // ---- MigrationTarget (canonical rows: [project_id, agent_id, message_id]) ----

    override fun exportRows(): List<ByteArray> = tx { c ->
        c.prepareStatement("SELECT project_id, agent_id, message_id FROM delivery ORDER BY project_id, agent_id, message_id").use { st ->
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(MigrationRowCodec.encode(listOf(rs.getString(1), rs.getString(2), rs.getString(3)))) }
            }
        }
    }

    override fun importRows(rows: List<ByteArray>) {
        tx { c ->
            c.prepareStatement("DELETE FROM delivery").use { it.executeUpdate() }
            c.prepareStatement("INSERT INTO delivery (project_id, agent_id, message_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING").use { ps ->
                for (row in rows) {
                    val f = MigrationRowCodec.decode(row) // [projectId, agentId, messageId]
                    ps.setString(1, f[0]); ps.setString(2, f[1]); ps.setString(3, f[2]); ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

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
