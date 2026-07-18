package com.tneff.cyppieagents.migration

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * CYP-220 — the client-side seam the Migrations-Screen consumes. **Stub-first (CYP-419 precedent):** the whole
 * `commonMain` UI is built + toothed against this interface + [StubMigrationApi]; the live swap is an HTTP impl
 * over the CYP-723 REST endpoints (post-M1, gates the LIVE wiring only) and does NOT change this contract or the
 * UI. Everything is per-(store, project) — a migration is never "global" (spec §1.2).
 */
interface MigrationApi {

    /** The store inventory for [projectId] (spec §2) — migratable + non-migratable groups, never a total. */
    suspend fun stores(projectId: String): MigrationStores

    /** The configured target DSN for [projectId] (spec §3), server-masked; `null` when none is stored. */
    suspend fun targetDsn(projectId: String): DsnView?

    /** Persist the target DSN draft (spec §3). The plaintext password is write-only — never returned (§3). */
    suspend fun saveTargetDsn(projectId: String, draft: DsnDraft)

    /**
     * Start migrating [storeKey] to the configured target (spec §4/§5). Emits [MigrationEvent.Progress] as the
     * phases advance, ending in [MigrationEvent.Succeeded] (receipt) or [MigrationEvent.Failed] (error). The
     * store's binding is READ_ONLY/MIGRATING for the window ([ReadOnlyReason.MIGRATION_WINDOW]).
     */
    fun start(projectId: String, storeKey: String): Flow<MigrationEvent>

    /** Roll back a store from a READ_ONLY / stuck window to the previous data (spec §7). Nothing is lost. */
    fun rollback(projectId: String, storeKey: String): Flow<MigrationEvent>

    /** Decommission (delete the OLD source) — the ONLY destructive action (spec §6.2). A separate endpoint. */
    suspend fun decommission(projectId: String, storeKey: String)

    /** The migration history for [projectId] (spec §8) — carries the durable-sink availability flag (BE-1). */
    suspend fun history(projectId: String): MigrationHistory
}

/** A DSN input draft (spec §3). The [password] is write-only ([CharArray], zeroizable) — never round-tripped. */
data class DsnDraft(
    val label: String,
    val host: String,
    val port: Int,
    val database: String,
    val user: String,
    val sslMode: String = "require",
    val password: CharArray? = null,
) {
    // data-class with a CharArray: identity-based equals/hashCode are fine here (drafts are not compared/keyed);
    // the override silences the array-in-data-class warning and avoids leaking password bytes into equality.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = label.hashCode()
}

/** A migration run event (spec §5/§6/§7): phase progress, then a terminal success (receipt) or failure. */
sealed interface MigrationEvent {
    data class Progress(val run: MigrationRun) : MigrationEvent
    data class Succeeded(val receipt: MigrationReceipt) : MigrationEvent
    data class Failed(val error: MigrationError) : MigrationEvent
}

/**
 * A scriptable [MigrationApi] for building + toothing the UI (mirrors [com.tneff.cyppieagents.connect.StubControlPlaneClient]).
 * No real migration — the UI renders against these honest values exactly as it will against the live REST impl.
 * Defaults model the pre-M1 reality: a handful of migratable stores, the §2.2 non-migratable rows shown with
 * reasons, and — crucially — history [HistoryAvailability.UNAVAILABLE] (the audit sink is not durably wired yet),
 * so §8 exercises the honest "not recorded" empty state rather than a silent "nothing happened".
 */
class StubMigrationApi(
    private val stores: MigrationStores = DEFAULT_STORES,
    private val targetDsn: DsnView? = null,
    private val history: MigrationHistory = MigrationHistory(HistoryAvailability.UNAVAILABLE, emptyList()),
    /** The scripted phase timeline for [start]; ends in [terminal]. */
    private val runPhases: List<MigrationRun> = emptyList(),
    private val terminal: MigrationEvent = MigrationEvent.Succeeded(MigrationReceipt(rows = 0, dsnLabel = "")),
) : MigrationApi {

    override suspend fun stores(projectId: String): MigrationStores = stores
    override suspend fun targetDsn(projectId: String): DsnView? = targetDsn
    override suspend fun saveTargetDsn(projectId: String, draft: DsnDraft) {}
    override suspend fun decommission(projectId: String, storeKey: String) {}
    override suspend fun history(projectId: String): MigrationHistory = history

    override fun start(projectId: String, storeKey: String): Flow<MigrationEvent> = flow {
        for (run in runPhases) emit(MigrationEvent.Progress(run))
        emit(terminal)
    }

    override fun rollback(projectId: String, storeKey: String): Flow<MigrationEvent> = flow {
        emit(terminal)
    }

    companion object {
        /** A representative default inventory (spec §2 examples) — real storeKeys, honest reasons. */
        val DEFAULT_STORES = MigrationStores(
            migratable = listOf(
                MigratableStore("report", "Report-Store", StoreBindingState.LOCAL),
                MigratableStore("event_log", "Ereignis-Log", StoreBindingState.BOUND, dsnLabel = "prod-pg"),
                MigratableStore("agent_events", "Agenten-Ereignisse", StoreBindingState.MIGRATING),
                MigratableStore(
                    "channel_share", "Kanal-Freigaben", StoreBindingState.READ_ONLY,
                    readOnlyReason = ReadOnlyReason.LEGACY_UNEVALUATED,
                ),
            ),
            unavailable = listOf(
                UnavailableStore("messages", "Nachrichten", UnavailableReason.NO_TARGET),
                UnavailableStore("dsn_registry", "DSN-Registry", UnavailableReason.INFRA),
                UnavailableStore("hub_secret", "Hub-Geheimnis", UnavailableReason.PENDING_DECISION),
                UnavailableStore("role_assignments", "Rollen-Zuweisungen", UnavailableReason.PENDING_DECISION),
            ),
        )
    }
}
