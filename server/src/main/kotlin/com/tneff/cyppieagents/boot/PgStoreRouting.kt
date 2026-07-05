package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.crypto.SecretCipher
import com.tneff.cyppieagents.db.BindingRegistry
import com.tneff.cyppieagents.db.BindingState
import com.tneff.cyppieagents.db.ConnectionProvider
import com.tneff.cyppieagents.tier.StoreResidencies
import javax.sql.DataSource

/**
 * CYP-220 Phase 6 S2 — the **boot factory** that selects a store's backing (File on our infra vs a bound
 * Postgres) **per binding**. A store routes to Postgres ONLY when it is **bound + [BindingState.ACTIVE]** and a
 * pool resolves; otherwise it uses the File fallback (unbound, or a MIGRATING/READ_ONLY window → reads come from
 * the retained source A — the write-reject during MIGRATING is Finding B / S3). Fail-safe: an unresolvable
 * binding falls back to File rather than failing the boot.
 */
object PgStoreRouting {

    /**
     * The live Postgres [DataSource] for a store iff it is bound+ACTIVE and a pool resolves; else null → File.
     *
     * **FAIL-CLOSED residency enforcement (P6-S2 finding):** a `MUST_STAY_HOME` store (roles/account/dsn_registry/
     * …) **NEVER** gets a user-DB route, **regardless of binding state** — the P6-S1 classification is enforced
     * HERE, at the single data-path placement point, so it is not a paper promise. (Latent today — no prod bind
     * path yet — but fail-closed by construction before the live-bind wiring lands.)
     */
    fun activeDataSource(
        storeKey: String,
        projectId: String,
        bindings: BindingRegistry,
        connections: ConnectionProvider,
    ): DataSource? {
        if (!StoreResidencies.isUserDbCapable(storeKey)) return null // MUST_STAY_HOME → never a user DB
        return bindings.binding(storeKey, projectId)
            ?.takeIf { it.state == BindingState.ACTIVE }
            ?.let { connections.forStore(storeKey, projectId) }
    }

    fun remoteTokenStore(
        projectId: String,
        bindings: BindingRegistry,
        connections: ConnectionProvider,
        cipher: SecretCipher,
        fileFallback: () -> RemoteTokenStore,
    ): RemoteTokenStore =
        activeDataSource("remote_token", projectId, bindings, connections)
            ?.let { PgRemoteTokenStore(it, cipher, projectId) } ?: fileFallback()

    fun projectConfigStore(
        projectId: String,
        bindings: BindingRegistry,
        connections: ConnectionProvider,
        cipher: SecretCipher,
        fallbackRepo: RepoConfig,
        secrets: Secrets,
        fileFallback: () -> ProjectConfigStore,
    ): ProjectConfigStore =
        activeDataSource("project_config", projectId, bindings, connections)
            ?.let { PgProjectConfigStore(it, cipher, fallbackRepo, secrets) } ?: fileFallback()
}
