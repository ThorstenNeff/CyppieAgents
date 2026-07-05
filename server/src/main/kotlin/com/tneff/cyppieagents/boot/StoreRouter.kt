package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.comm.ChannelShareStore
import com.tneff.cyppieagents.comm.DeliveryLog
import com.tneff.cyppieagents.connector.SessionStore
import com.tneff.cyppieagents.crypto.SecretCipher
import com.tneff.cyppieagents.db.BindingRegistry
import com.tneff.cyppieagents.db.ConnectionProvider

/**
 * CYP-220 Live-Bind-Wiring (W1) — the **one seam** that makes every latent P6 guard fire. It binds the DB infra
 * ([bindings] + [connections] + [cipher]) once and exposes **per-op** accessors that delegate to [PgStoreRouting]
 * with a File fallback. A consumer holds a `StoreRouter` (not a boot-cached store handle) and calls the accessor
 * **on every access**, so the S1 residency guard + the S3 migration-freeze are re-evaluated each op — a
 * post-boot `MIGRATING` flip is seen immediately (the boot-cached-handle bypass, the logged S3-finding, is
 * structurally impossible here).
 *
 * **INERT by construction:** with an empty [bindings] registry (the boot default until an operator binds a
 * store — a later admin-API slice), `activeDataSource` is always null → every accessor returns its File
 * fallback → **zero behavior change**. **Fail-safe cipher:** a null [cipher] (no `CYPPIE_MASTER_KEY`) means a
 * secret store is **never** routed to a user DB — it stays File (a secret must never land on Postgres without
 * encryption), independent of any binding.
 *
 * W1 wires the LIGHT stores (secret + non-secret + session + delivery). The heavy, stateful stores
 * (event_log / agent_events / report — JDBC+WAL+SharedFlow / in-process counter) take a MEMOIZING pg-factory
 * and land in a later sub-slice ([PgStoreRouting.eventSink]/`agentEventStore`/`reportStore`).
 */
class StoreRouter(
    private val bindings: BindingRegistry,
    private val connections: ConnectionProvider,
    /** null = no master key configured → secret stores stay File (fail-safe, never plaintext-capable on a user DB). */
    private val cipher: SecretCipher?,
    private val secrets: Secrets,
    private val fallbackRepo: RepoConfig,
) {
    // ---- secret stores (require the cipher; null cipher → File, fail-safe) ----

    fun remoteTokenStore(projectId: String, fileFallback: () -> RemoteTokenStore): RemoteTokenStore {
        val c = cipher ?: return fileFallback()
        return PgStoreRouting.remoteTokenStore(projectId, bindings, connections, c, fileFallback)
    }

    fun projectConfigStore(projectId: String, fileFallback: () -> ProjectConfigStore): ProjectConfigStore {
        val c = cipher ?: return fileFallback()
        return PgStoreRouting.projectConfigStore(projectId, bindings, connections, c, fallbackRepo, secrets, fileFallback)
    }

    // ---- non-secret stores (no cipher) ----

    fun agentOverrideStore(projectId: String, fileFallback: () -> AgentOverrideStore): AgentOverrideStore =
        PgStoreRouting.agentOverrideStore(projectId, bindings, connections, fileFallback)

    fun channelShareStore(projectId: String, fileFallback: () -> ChannelShareStore): ChannelShareStore =
        PgStoreRouting.channelShareStore(projectId, bindings, connections, fileFallback)

    fun sessionStore(projectId: String, fileFallback: () -> SessionStore): SessionStore =
        PgStoreRouting.sessionStore(projectId, bindings, connections, fileFallback)

    fun deliveryLog(projectId: String, fileFallback: () -> DeliveryLog): DeliveryLog =
        PgStoreRouting.deliveryLog(projectId, bindings, connections, fileFallback)
}
