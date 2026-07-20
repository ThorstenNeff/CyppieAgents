package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.agentevents.AgentEventStore
import com.tneff.cyppieagents.agentevents.MigrationGatedAgentEventStore
import com.tneff.cyppieagents.comm.ChannelShareStore
import com.tneff.cyppieagents.comm.DeliveryLog
import com.tneff.cyppieagents.comm.MigrationGatedDeliveryLog
import com.tneff.cyppieagents.comm.PgChannelShareStore
import com.tneff.cyppieagents.comm.PgDeliveryLog
import com.tneff.cyppieagents.connector.MigrationGatedSessionStore
import com.tneff.cyppieagents.connector.PgSessionStore
import com.tneff.cyppieagents.connector.SessionStore
import com.tneff.cyppieagents.crypto.SecretCipher
import com.tneff.cyppieagents.events.EventSink
import com.tneff.cyppieagents.events.MigrationGatedEventSink
import com.tneff.cyppieagents.report.MigrationGatedReportStore
import com.tneff.cyppieagents.report.ReportStore
import com.tneff.cyppieagents.db.BindingRegistry
import com.tneff.cyppieagents.db.BindingReason
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
    fun inMigrationWindow(storeKey: String, projectId: String, bindings: BindingRegistry): Boolean =
        windowReason(storeKey, projectId, bindings) != null

    /**
     * CYP-720 (BE-8) — the same predicate as [inMigrationWindow] but carrying **WHY** the write-lock is held
     * ([BindingReason]), or null when the store is not locked at all. [inMigrationWindow] is DERIVED from this so
     * the "is it locked" and "why is it locked" answers are single-sourced and cannot drift apart.
     *
     * The reason is what makes the 409 honest: a [BindingReason.MIGRATION_WINDOW] lock is temporary and
     * retry-after-switch is real, whereas a [BindingReason.LEGACY_UNEVALUATED] lock (the CYP-714 fail-closed
     * coercion) is held until an operator acts — the same `store_migrating` copy for both sent that operator to
     * wait for a switch that was never coming.
     */
    fun windowReason(storeKey: String, projectId: String, bindings: BindingRegistry): BindingReason? {
        if (!StoreResidencies.isUserDbCapable(storeKey)) return null // MUST_STAY_HOME → never windowed
        val binding = bindings.binding(storeKey, projectId) ?: return null // unbound → not a window
        return when (binding.state) {
            BindingState.MIGRATING, BindingState.READ_ONLY -> binding.reason
            BindingState.ACTIVE -> null
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
        windowReason("remote_token", projectId, bindings)?.let { return MigrationGatedRemoteTokenStore(fileFallback(), it) }
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
        windowReason("project_config", projectId, bindings)?.let { return MigrationGatedProjectConfigStore(fileFallback(), it) }
        return activeDataSource("project_config", projectId, bindings, connections)
            ?.let { PgProjectConfigStore(it, cipher, fallbackRepo, secrets) } ?: fileFallback()
    }

    // ---- S4: non-secret user-DB-capable stores (no cipher) ----

    fun agentOverrideStore(
        projectId: String,
        bindings: BindingRegistry,
        connections: ConnectionProvider,
        fileFallback: () -> AgentOverrideStore,
    ): AgentOverrideStore {
        windowReason("agent_override", projectId, bindings)?.let { return MigrationGatedAgentOverrideStore(fileFallback(), it) }
        return activeDataSource("agent_override", projectId, bindings, connections)
            ?.let { PgAgentOverrideStore(it) } ?: fileFallback()
    }

    fun channelShareStore(
        projectId: String,
        bindings: BindingRegistry,
        connections: ConnectionProvider,
        fileFallback: () -> ChannelShareStore,
    ): ChannelShareStore {
        windowReason("channel_share", projectId, bindings)?.let { return MigrationGatedChannelShareStore(fileFallback(), it) }
        return activeDataSource("channel_share", projectId, bindings, connections)
            ?.let { PgChannelShareStore(it) } ?: fileFallback()
    }

    // ---- S5: high-volume append-only stores. UNLIKE the JSON/File stores above, these are HEAVYWEIGHT +
    //         LONG-LIVED (a JDBC connection + WAL + a live SharedFlow + seq continuity), so `pg` is a
    //         MEMOIZING factory the caller (live-wiring) supplies — it must return the SAME instance per
    //         DataSource, never construct one per op (that would reopen the DB and orphan live subscribers).
    //         The accessor only SELECTS (residency + window + bound/ACTIVE), it does not own the lifetime.

    fun eventSink(
        projectId: String,
        bindings: BindingRegistry,
        connections: ConnectionProvider,
        pg: (DataSource) -> EventSink,
        fileFallback: () -> EventSink,
    ): EventSink {
        windowReason("event_log", projectId, bindings)?.let { return MigrationGatedEventSink(fileFallback(), it) }
        return activeDataSource("event_log", projectId, bindings, connections)?.let { pg(it) } ?: fileFallback()
    }

    fun agentEventStore(
        projectId: String,
        bindings: BindingRegistry,
        connections: ConnectionProvider,
        pg: (DataSource) -> AgentEventStore,
        fileFallback: () -> AgentEventStore,
    ): AgentEventStore {
        windowReason("agent_events", projectId, bindings)?.let { return MigrationGatedAgentEventStore(fileFallback(), it) }
        return activeDataSource("agent_events", projectId, bindings, connections)?.let { pg(it) } ?: fileFallback()
    }

    // ---- S6: non-secret lightweight stores (no cipher; inline construction like S4 — no WAL/flow/seq). ----

    fun sessionStore(
        projectId: String,
        bindings: BindingRegistry,
        connections: ConnectionProvider,
        fileFallback: () -> SessionStore,
    ): SessionStore {
        windowReason("session", projectId, bindings)?.let { return MigrationGatedSessionStore(fileFallback(), it) }
        return activeDataSource("session", projectId, bindings, connections)?.let { PgSessionStore(it) } ?: fileFallback()
    }

    fun deliveryLog(
        projectId: String,
        bindings: BindingRegistry,
        connections: ConnectionProvider,
        fileFallback: () -> DeliveryLog,
    ): DeliveryLog {
        windowReason("delivery", projectId, bindings)?.let { return MigrationGatedDeliveryLog(fileFallback(), it) }
        return activeDataSource("delivery", projectId, bindings, connections)?.let { PgDeliveryLog(it) } ?: fileFallback()
    }

    /**
     * S6 report — stateful (in-process `rep-N` counter), so `pg` is a MEMOIZING factory (like the event stores),
     * not per-op construction: the counter must be coherent across generate calls on one instance.
     */
    fun reportStore(
        projectId: String,
        bindings: BindingRegistry,
        connections: ConnectionProvider,
        pg: (DataSource) -> ReportStore,
        fileFallback: () -> ReportStore,
    ): ReportStore {
        windowReason("report", projectId, bindings)?.let { return MigrationGatedReportStore(fileFallback(), it) }
        return activeDataSource("report", projectId, bindings, connections)?.let { pg(it) } ?: fileFallback()
    }
}
