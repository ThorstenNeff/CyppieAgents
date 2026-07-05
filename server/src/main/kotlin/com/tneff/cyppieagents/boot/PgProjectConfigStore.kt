package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.crypto.EncryptedSecret
import com.tneff.cyppieagents.crypto.SecretAad
import com.tneff.cyppieagents.crypto.SecretCipher
import com.tneff.cyppieagents.db.FlywayMigrator
import com.tneff.cyppieagents.db.MigrationRowCodec
import com.tneff.cyppieagents.db.MigrationTarget
import com.tneff.cyppieagents.model.ApiKeyView
import com.tneff.cyppieagents.model.RepoConfigView
import com.tneff.cyppieagents.routing.BadRequestException
import java.sql.Connection
import javax.sql.DataSource

/**
 * CYP-220 Phase 6 S2 — the **Postgres** [ProjectConfigStore] impl. Behaviour-identical to
 * [FileProjectConfigStore] (same validation via the shared [isPlausibleRepoUrl]/[isPlausibleApiKey], same
 * fallback-to-boot resolution, same masking, per-`projectId` scoping, blank-key-removes-nothing fail-closed),
 * but the **API key is encrypted at rest via [SecretCipher]** (AAD `project_config|{projectId}|api_key`).
 * **The plaintext key NEVER touches a PG row** (only `api_key_ct` + `api_key_ver`); `repo_url`/`repo_branch`
 * are non-secret. Restart-safe: a fresh instance with the same master key decrypts prior rows.
 */
class PgProjectConfigStore(
    private val dataSource: DataSource,
    private val cipher: SecretCipher,
    private val fallbackRepo: RepoConfig,
    private val secrets: Secrets,
    migrate: Boolean = true,
) : ProjectConfigStore, MigrationTarget {

    init { if (migrate) FlywayMigrator.migrate(dataSource, "classpath:db/migration/projectconfig") }

    private fun aad(projectId: String) = SecretAad("project_config", projectId, "api_key")

    // ---- resolution ----

    override fun resolvedRepo(projectId: String): RepoConfig = repoRow(projectId) ?: fallbackRepo

    override fun resolvedApiKey(projectId: String): String? =
        decryptedKey(projectId) ?: secrets.apiKeyFor(projectId)

    // ---- views ----

    override fun repoView(projectId: String): RepoConfigView {
        val repo = resolvedRepo(projectId)
        return if (repo.url.isBlank()) RepoConfigView(configured = false)
        else RepoConfigView(configured = true, url = repo.url, branch = repo.branch)
    }

    override fun apiKeyView(projectId: String): ApiKeyView {
        val key = decryptedKey(projectId) ?: secrets.apiKeyFor(projectId)
        return if (key.isNullOrBlank()) ApiKeyView(set = false, masked = null)
        else ApiKeyView(set = true, masked = Secrets.mask(key))
    }

    // ---- mutations ----

    override fun setRepo(projectId: String, url: String, branch: String): RepoConfigView {
        val u = url.trim()
        if (!isPlausibleRepoUrl(u)) throw BadRequestException("invalid repository url", code = "invalid_repo_url")
        val b = branch.trim().ifBlank { "main" }
        tx { c ->
            c.prepareStatement(
                "INSERT INTO project_config (project_id, repo_url, repo_branch) VALUES (?, ?, ?) " +
                    "ON CONFLICT (project_id) DO UPDATE SET repo_url = EXCLUDED.repo_url, repo_branch = EXCLUDED.repo_branch",
            ).use { it.setString(1, projectId); it.setString(2, u); it.setString(3, b); it.executeUpdate() }
        }
        return RepoConfigView(configured = true, url = u, branch = b)
    }

    override fun setApiKey(projectId: String, key: String): ApiKeyView {
        val k = key.trim()
        if (!isPlausibleApiKey(k)) throw BadRequestException("invalid api key", code = "invalid_api_key")
        val enc = cipher.encrypt(k, aad(projectId))
        tx { c ->
            c.prepareStatement(
                "INSERT INTO project_config (project_id, api_key_ct, api_key_ver) VALUES (?, ?, ?) " +
                    "ON CONFLICT (project_id) DO UPDATE SET api_key_ct = EXCLUDED.api_key_ct, api_key_ver = EXCLUDED.api_key_ver",
            ).use { it.setString(1, projectId); it.setBytes(2, enc.ciphertext); it.setInt(3, enc.keyVersion); it.executeUpdate() }
        }
        return ApiKeyView(set = true, masked = Secrets.mask(k))
    }

    override fun remove(projectId: String): Boolean {
        if (projectId.isBlank()) return false // fail-closed: never an unscoped clear
        return tx { c ->
            c.prepareStatement("DELETE FROM project_config WHERE project_id = ?")
                .use { it.setString(1, projectId); it.executeUpdate() } > 0
        }
    }

    // ---- MigrationTarget (rows carry the LOGICAL key transiently; the target RE-ENCRYPTS on import) ----

    override fun exportRows(): List<ByteArray> = tx { c ->
        c.prepareStatement("SELECT project_id, repo_url, repo_branch, api_key_ct, api_key_ver FROM project_config").use { st ->
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val pid = rs.getString(1)
                        val ct = rs.getBytes(4)
                        val key = if (ct != null) cipher.decrypt(EncryptedSecret(ct, rs.getInt(5)), aad(pid)) else ""
                        add(MigrationRowCodec.encode(listOf(pid, rs.getString(2) ?: "", rs.getString(3) ?: "", key)))
                    }
                }
            }
        }
    }

    override fun importRows(rows: List<ByteArray>) = tx { c ->
        c.prepareStatement("DELETE FROM project_config").use { it.executeUpdate() }
        for (row in rows) {
            val f = MigrationRowCodec.decode(row) // [projectId, repoUrl, repoBranch, apiKeyPlaintext]
            val enc = if (f[3].isNotEmpty()) cipher.encrypt(f[3], aad(f[0])) else null
            c.prepareStatement("INSERT INTO project_config (project_id, repo_url, repo_branch, api_key_ct, api_key_ver) VALUES (?, ?, ?, ?, ?)").use {
                it.setString(1, f[0])
                it.setString(2, f[1].ifBlank { null })
                it.setString(3, f[2].ifBlank { null })
                if (enc != null) { it.setBytes(4, enc.ciphertext); it.setInt(5, enc.keyVersion) } else { it.setBytes(4, null); it.setNull(5, java.sql.Types.INTEGER) }
                it.executeUpdate()
            }
        }
        Unit
    }

    // ---- helpers ----

    private fun repoRow(projectId: String): RepoConfig? = tx { c ->
        c.prepareStatement("SELECT repo_url, repo_branch FROM project_config WHERE project_id = ?").use { st ->
            st.setString(1, projectId)
            st.executeQuery().use { rs ->
                if (rs.next() && !rs.getString(1).isNullOrBlank()) RepoConfig(rs.getString(1), rs.getString(2)?.ifBlank { null } ?: "main") else null
            }
        }
    }

    private fun decryptedKey(projectId: String): String? = tx { c ->
        c.prepareStatement("SELECT api_key_ct, api_key_ver FROM project_config WHERE project_id = ?").use { st ->
            st.setString(1, projectId)
            st.executeQuery().use { rs ->
                if (rs.next()) {
                    val ct = rs.getBytes(1)
                    if (ct != null) cipher.decrypt(EncryptedSecret(ct, rs.getInt(2)), aad(projectId)).takeIf { it.isNotBlank() } else null
                } else null
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
