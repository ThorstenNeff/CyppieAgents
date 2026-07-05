package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.crypto.SecretCipher
import com.tneff.cyppieagents.db.BindingRegistry
import com.tneff.cyppieagents.db.BindingState
import com.tneff.cyppieagents.db.ConnectionProvider
import com.tneff.cyppieagents.tier.StoreResidencies
import javax.sql.DataSource

/**
 * CYP-220 Phase 6 S2/S3 — the **boot factory** that selects a store's backing (File on our infra vs a bound
 * Postgres) **per binding**. A store routes to Postgres ONLY when it is **bound + [BindingState.ACTIVE]** and a
 * pool resolves; unbound → the File fallback.
 *
 * **The single store-access placement point** — the only way a caller obtains a store instance, so residency
 * (S2) and the migration write-freeze (S3, Finding B) are both enforced HERE, no bypass path:
 * - **Residency (S2):** a MUST_STAY_HOME store never routes to a user DB (in [activeDataSource]).
 * - **Read-only window (S3):** while a store's binding is MIGRATING/READ_ONLY, the accessor returns a
 *   [MigrationGatedRemoteTokenStore]/[MigrationGatedProjectConfigStore] — reads pass through to source A, every
 *   write is rejected (`store_migrating` 409) so no write is lost in A or duplicated into B (see [MigrationGate]).
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

    /**
     * True iff a **user-DB-capable** store's binding is in a **write-freeze window** — the MIGRATING/READ_ONLY
     * interval between the copy `A → B` and the atomic rebind to ACTIVE (Design §4.3, Finding B). During it reads
     * stay served from source A and writes MUST be rejected. A MUST_STAY_HOME store never holds a user-DB binding
     * (residency, S2), so it is never in a window here — its own writes are unaffected.
     */
    fun inMigrationWindow(storeKey: String, projectId: String, bindings: BindingRegistry): Boolean {
        if (!StoreResidencies.isUserDbCapable(storeKey)) return false
        return when (bindings.binding(storeKey, projectId)?.state) {
            BindingState.MIGRATING, BindingState.READ_ONLY -> true
            else -> false
        }
    }

    fun remoteTokenStore(
        projectId: String,
        bindings: BindingRegistry,
        connections: ConnectionProvider,
        cipher: SecretCipher,
        fileFallback: () -> RemoteTokenStore,
    ): RemoteTokenStore {
        // S3: during the migration window, reads come from source A (the File fallback for the File→Pg
        // initial-offload the migrator performs today); writes are frozen (store_migrating 409).
        if (inMigrationWindow("remote_token", projectId, bindings)) return MigrationGatedRemoteTokenStore(fileFallback())
        return activeDataSource("remote_token", projectId, bindings, connections)
            ?.let { PgRemoteTokenStore(it, cipher, projectId) } ?: fileFallback()
    }

    fun projectConfigStore(
        projectId: String,
        bindings: BindingRegistry,
        connections: ConnectionProvider,
        cipher: SecretCipher,
        fallbackRepo: RepoConfig,
        secrets: Secrets,
        fileFallback: () -> ProjectConfigStore,
    ): ProjectConfigStore {
        if (inMigrationWindow("project_config", projectId, bindings)) return MigrationGatedProjectConfigStore(fileFallback())
        return activeDataSource("project_config", projectId, bindings, connections)
            ?.let { PgProjectConfigStore(it, cipher, fallbackRepo, secrets) } ?: fileFallback()
    }
}
