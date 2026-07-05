package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.db.FlywayMigrator
import com.tneff.cyppieagents.db.MigrationRowCodec
import com.tneff.cyppieagents.db.MigrationTarget
import java.sql.Connection
import javax.sql.DataSource

/**
 * CYP-220 Phase 6 S6 — the **Postgres** [SessionStore]. Behaviour-identical to [JsonFileSessionStore]: one row
 * per `(project_id, agent_id)`, `find`/`upsert`/`clear`. **NON-SECRET** (a session-id resume binding, no
 * credential), so no [com.tneff.cyppieagents.crypto.SecretCipher].
 *
 * **The `upsert` createdAt-preserve is a read-merge-write** (keep the stored `createdAt` only while the SAME
 * `sessionId` is rewritten — a lastActivity refresh — else reset to `now` for a new conversation). The File impl
 * does it atomically under one `synchronized(lock)`. Here it is pushed **into a single atomic statement** —
 * `INSERT … ON CONFLICT DO UPDATE` with a `CASE` on the existing row's `session_id` — so there is **no
 * Kotlin-side read-then-write window at all** (tighter than a tx + `SELECT … FOR UPDATE`): Postgres evaluates
 * the `CASE` against the conflicting row under its row lock, so two concurrent upserts can never lose the
 * preserved `createdAt` (proven by the pre-seeded concurrency tooth). No ordering counter ⇒ no import realign.
 */
class PgSessionStore(
    private val dataSource: DataSource,
    migrate: Boolean = true,
) : SessionStore, MigrationTarget {

    init { if (migrate) FlywayMigrator.migrate(dataSource, "classpath:db/migration/session") }

    override fun find(projectId: String, agentId: String): SessionEntry? = tx { c ->
        c.prepareStatement("SELECT project_id, agent_id, session_id, created_at, last_activity FROM session WHERE project_id = ? AND agent_id = ?").use { st ->
            st.setString(1, projectId); st.setString(2, agentId)
            st.executeQuery().use { rs -> if (rs.next()) readRow(rs) else null }
        }
    }

    override fun upsert(projectId: String, agentId: String, sessionId: String, now: Long) {
        // The createdAt-preserve merge is ATOMIC in one statement: on conflict, created_at keeps the stored value
        // iff the session_id is unchanged (a refresh), else resets to now (a new conversation). Same rule as
        // InMemorySessionStore.upsert, no read-then-write window.
        tx { c ->
            c.prepareStatement(
                "INSERT INTO session (project_id, agent_id, session_id, created_at, last_activity) VALUES (?, ?, ?, ?, ?) " +
                    "ON CONFLICT (project_id, agent_id) DO UPDATE SET " +
                    "session_id = EXCLUDED.session_id, " +
                    "created_at = CASE WHEN session.session_id = EXCLUDED.session_id THEN session.created_at ELSE EXCLUDED.created_at END, " +
                    "last_activity = EXCLUDED.last_activity",
            ).use { it.setString(1, projectId); it.setString(2, agentId); it.setString(3, sessionId); it.setLong(4, now); it.setLong(5, now); it.executeUpdate() }
        }
    }

    override fun clear(projectId: String, agentId: String) {
        tx { c ->
            c.prepareStatement("DELETE FROM session WHERE project_id = ? AND agent_id = ?").use { it.setString(1, projectId); it.setString(2, agentId); it.executeUpdate() }
        }
    }

    // ---- MigrationTarget (canonical rows: [project_id, agent_id, session_id, created_at, last_activity]) ----

    override fun exportRows(): List<ByteArray> = tx { c ->
        c.prepareStatement("SELECT project_id, agent_id, session_id, created_at, last_activity FROM session ORDER BY project_id, agent_id").use { st ->
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(MigrationRowCodec.encode(listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4).toString(), rs.getLong(5).toString()))) }
            }
        }
    }

    override fun importRows(rows: List<ByteArray>) {
        tx { c ->
            c.prepareStatement("DELETE FROM session").use { it.executeUpdate() }
            c.prepareStatement("INSERT INTO session (project_id, agent_id, session_id, created_at, last_activity) VALUES (?, ?, ?, ?, ?)").use { ps ->
                for (row in rows) {
                    val f = MigrationRowCodec.decode(row) // [projectId, agentId, sessionId, createdAt, lastActivity]
                    ps.setString(1, f[0]); ps.setString(2, f[1]); ps.setString(3, f[2]); ps.setLong(4, f[3].toLong()); ps.setLong(5, f[4].toLong())
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    private fun readRow(rs: java.sql.ResultSet): SessionEntry =
        SessionEntry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5))

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
