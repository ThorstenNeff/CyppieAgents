package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.db.FlywayMigrator
import com.tneff.cyppieagents.db.MigrationRowCodec
import com.tneff.cyppieagents.db.MigrationTarget
import java.sql.Connection
import javax.sql.DataSource

/**
 * CYP-220 Phase 6 S4 — the **Postgres** [ChannelShareStore] impl. Behaviour-identical to
 * [FileChannelShareStore]: the same "empty grantees → revoke" rule (a share to nobody, or only to the owner's
 * own project, is a no-op hole), the same per-`channelId` granularity (a share never widens to the owner's
 * other channels), and the same fail-closed [sharedInboundChannelIds] (a blank active project reaches nothing).
 * **Non-secret** (a cross-project authorization gate, no credentials), so no
 * [com.tneff.cyppieagents.crypto.SecretCipher] — the whole [ChannelShareRecord] is stored as its canonical
 * [CommJson] form (one text column), preserving the `sharedWith`/`consents` sets exactly.
 */
class PgChannelShareStore(
    private val dataSource: DataSource,
    private val clock: () -> Long = { System.currentTimeMillis() },
    migrate: Boolean = true,
) : ChannelShareStore, MigrationTarget {

    init { if (migrate) FlywayMigrator.migrate(dataSource, "classpath:db/migration/channelshare") }

    override fun share(channelId: String, ownerProjectId: String, sharedWith: Set<String>): ChannelShareRecord {
        val now = clock()
        // Same "empty grantees → revoke" rule as File (single-sourced [computeShareRecord], no drift).
        val rec = computeShareRecord(channelId, ownerProjectId, sharedWith, now)
            ?: run { deleteRecord(channelId); return emptyShareRecord(channelId, ownerProjectId, now) }
        upsert(rec)
        return rec
    }

    override fun revoke(channelId: String): Boolean = deleteRecord(channelId)

    override fun record(channelId: String): ChannelShareRecord? = tx { c ->
        c.prepareStatement("SELECT record_json FROM channel_share WHERE channel_id = ?").use { st ->
            st.setString(1, channelId)
            st.executeQuery().use { rs -> if (rs.next()) decode(rs.getString(1)) else null }
        }
    }

    override fun sharedInboundChannelIds(activeProjectId: String): Set<String> {
        if (activeProjectId.isBlank()) return emptySet() // fail-closed: blank active reaches nothing
        return allRecords().filter { activeProjectId in it.sharedWith }.map { it.channelId }.toSet()
    }

    override fun removeProject(projectId: String): Int {
        if (projectId.isBlank()) return 0 // fail-closed: never an unscoped purge
        return tx { c -> mutateBy(c) { purgeProjectFromShare(it, projectId) } }
    }

    override fun sweepOrphans(liveProjectIds: Set<String>, federationEnabled: Boolean): Int =
        tx { c -> mutateBy(c) { sweepOrphanFromShare(it, liveProjectIds, federationEnabled) } }

    /** Apply a per-record [decide] across the whole gate within one tx (drops/narrows committed together). */
    private fun mutateBy(c: Connection, decide: (ChannelShareRecord) -> SharePurge): Int {
        val all = c.prepareStatement("SELECT record_json FROM channel_share").use { st ->
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(decode(rs.getString(1))) } }
        }
        var touched = 0
        for (rec in all) {
            when (val r = decide(rec)) {
                SharePurge.Drop -> { deleteOn(c, rec.channelId); touched++ }
                is SharePurge.Narrow -> { upsertOn(c, r.record); touched++ }
                SharePurge.Untouched -> {}
            }
        }
        return touched
    }

    // ---- MigrationTarget (canonical rows: [channel_id, owner_project_id, record_json]) ----

    override fun exportRows(): List<ByteArray> = tx { c ->
        c.prepareStatement("SELECT channel_id, owner_project_id, record_json FROM channel_share ORDER BY channel_id").use { st ->
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(MigrationRowCodec.encode(listOf(rs.getString(1), rs.getString(2), rs.getString(3)))) }
            }
        }
    }

    override fun importRows(rows: List<ByteArray>) = tx { c ->
        c.prepareStatement("DELETE FROM channel_share").use { it.executeUpdate() }
        for (row in rows) {
            val f = MigrationRowCodec.decode(row) // [channelId, ownerProjectId, recordJson]
            c.prepareStatement("INSERT INTO channel_share (channel_id, owner_project_id, record_json) VALUES (?, ?, ?)")
                .use { it.setString(1, f[0]); it.setString(2, f[1]); it.setString(3, f[2]); it.executeUpdate() }
        }
        Unit
    }

    private fun allRecords(): List<ChannelShareRecord> = tx { c ->
        c.prepareStatement("SELECT record_json FROM channel_share").use { st ->
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(decode(rs.getString(1))) } }
        }
    }

    private fun upsert(rec: ChannelShareRecord) = tx { c -> upsertOn(c, rec) }

    /** CYP-910: the upsert body on an EXISTING connection, so [removeProject] can batch drops+narrows in one tx. */
    private fun upsertOn(c: Connection, rec: ChannelShareRecord) {
        c.prepareStatement(
            "INSERT INTO channel_share (channel_id, owner_project_id, record_json) VALUES (?, ?, ?) " +
                "ON CONFLICT (channel_id) DO UPDATE SET owner_project_id = EXCLUDED.owner_project_id, record_json = EXCLUDED.record_json",
        ).use { it.setString(1, rec.channelId); it.setString(2, rec.ownerProjectId); it.setString(3, CommJson.encodeToString(rec)); it.executeUpdate() }
    }

    private fun deleteRecord(channelId: String): Boolean = tx { c -> deleteOn(c, channelId) }

    /** CYP-910: the delete body on an EXISTING connection (shared by [revoke] and [removeProject]). */
    private fun deleteOn(c: Connection, channelId: String): Boolean =
        c.prepareStatement("DELETE FROM channel_share WHERE channel_id = ?")
            .use { it.setString(1, channelId); it.executeUpdate() } > 0

    private fun decode(json: String): ChannelShareRecord = CommJson.decodeFromString(json)

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
