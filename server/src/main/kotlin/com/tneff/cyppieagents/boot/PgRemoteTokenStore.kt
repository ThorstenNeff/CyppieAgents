package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.crypto.EncryptedSecret
import com.tneff.cyppieagents.crypto.SecretAad
import com.tneff.cyppieagents.crypto.SecretCipher
import com.tneff.cyppieagents.db.FlywayMigrator
import com.tneff.cyppieagents.db.MigrationRowCodec
import com.tneff.cyppieagents.db.MigrationTarget
import java.sql.Connection
import javax.sql.DataSource

/**
 * CYP-220 Phase 6 S2 — the **Postgres** [RemoteTokenStore] impl. Behaviour-identical to [FileRemoteTokenStore],
 * but the bearer token is **encrypted at rest via [SecretCipher]** (AAD `remote_token|{projectId}|{agentId}` —
 * a moved ciphertext won't decrypt). **The plaintext token NEVER touches a PG row** (only `token_ct` = the
 * AEAD ciphertext + `token_ver`), so a user with read on their own BYO DB cannot read the tokens — the master
 * key (not the user) protects them. Restart-safe: a fresh instance with the same master key decrypts prior rows.
 */
class PgRemoteTokenStore(
    private val dataSource: DataSource,
    private val cipher: SecretCipher,
    private val projectId: String,
    migrate: Boolean = true,
) : RemoteTokenStore, MigrationTarget {

    init { if (migrate) FlywayMigrator.migrate(dataSource, "classpath:db/migration/remotetoken") }

    private fun aad(agentId: String) = SecretAad("remote_token", projectId, agentId)

    override fun all(): Map<String, String> = tx { c ->
        c.prepareStatement("SELECT agent_id, token_ct, token_ver FROM remote_token").use { st ->
            st.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        val agentId = rs.getString(1)
                        put(agentId, cipher.decrypt(EncryptedSecret(rs.getBytes(2), rs.getInt(3)), aad(agentId)))
                    }
                }
            }
        }
    }

    override fun put(agentId: String, token: String) = tx { c ->
        val enc = cipher.encrypt(token, aad(agentId))
        c.prepareStatement(
            "INSERT INTO remote_token (agent_id, token_ct, token_ver) VALUES (?, ?, ?) " +
                "ON CONFLICT (agent_id) DO UPDATE SET token_ct = EXCLUDED.token_ct, token_ver = EXCLUDED.token_ver",
        ).use { it.setString(1, agentId); it.setBytes(2, enc.ciphertext); it.setInt(3, enc.keyVersion); it.executeUpdate() }
        Unit
    }

    override fun remove(agentId: String) = tx { c ->
        c.prepareStatement("DELETE FROM remote_token WHERE agent_id = ?").use { it.setString(1, agentId); it.executeUpdate() }
        Unit
    }

    // ---- MigrationTarget (rows carry the LOGICAL token; the migrator holds them in-memory transiently, the
    //      target RE-ENCRYPTS on import → plaintext never at rest on either side) ----

    override fun exportRows(): List<ByteArray> = all().map { (a, t) -> MigrationRowCodec.encode(listOf(a, t)) }

    override fun importRows(rows: List<ByteArray>) = tx { c ->
        c.prepareStatement("DELETE FROM remote_token").use { it.executeUpdate() }
        for (row in rows) {
            val f = MigrationRowCodec.decode(row)
            val enc = cipher.encrypt(f[1], aad(f[0]))
            c.prepareStatement("INSERT INTO remote_token (agent_id, token_ct, token_ver) VALUES (?, ?, ?)")
                .use { it.setString(1, f[0]); it.setBytes(2, enc.ciphertext); it.setInt(3, enc.keyVersion); it.executeUpdate() }
        }
        Unit
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
