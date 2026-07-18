package com.tneff.cyppieagents.migration

/**
 * CYP-220 — the CLIENT model for the operator Migrations-Screen. Mirrors the backend truth
 * (`db/StoreBinding.kt`, `db/MigrationAudit.kt`, `db/StoreMigrator.kt`, `db/DsnRegistry.kt`) form-neutrally to
 * the still-open §6.4 Auftraggeber-Weiche: it lets the UI state the four surface-truths (H1–H4, spec §0) and
 * NEVER invent a scope/percentage. All fields are secret-free (labels, row counts, the server-masked `***last4`).
 *
 * **Stub-first (CYP-419 precedent):** the UI is built against [MigrationApi] + [StubMigrationApi]; the live swap
 * (a real HTTP `MigrationApi` over the CYP-723 REST endpoints) is post-M1 and does NOT change this model.
 */

/**
 * A store's binding state as the UI shows it — [StoreBinding.BindingState] (ACTIVE→[BOUND], MIGRATING, READ_ONLY)
 * plus [LOCAL] for the **absence** of a binding (an unbound store, spec §2.1 — a named state, never an empty cell).
 */
enum class StoreBindingState { LOCAL, BOUND, MIGRATING, READ_ONLY }

/**
 * CYP-720 (BE-8) — **why** a binding is READ_ONLY. CYP-714 coerces a legacy state-less binding fail-closed to
 * READ_ONLY, and a live migration ALSO sets READ_ONLY — so READ_ONLY alone is ambiguous. This provenance signal,
 * supplied by the backend, lets the UI tell the migration window from a legacy freeze (spec §11.4). `null` on the
 * client model when the state is not READ_ONLY; a READ_ONLY store WITHOUT a reason is rendered honestly as unknown
 * (never silently assumed to be a migration).
 */
enum class ReadOnlyReason {
    /** READ_ONLY because a migration is in its write-freeze window (the active, expected case). */
    MIGRATION_WINDOW,

    /** READ_ONLY because it is a pre-`state`-era binding CYP-714 fail-closed — NOT migrating; needs re-evaluation. */
    LEGACY_UNEVALUATED,
}

/**
 * A migratable store row (spec §2.1). [displayName] is the plaintext name (never the raw [storeKey]); [dsnLabel]
 * is the target-instance label when [state] is [StoreBindingState.BOUND]; [readOnlyReason] is set iff
 * [state] == [StoreBindingState.READ_ONLY] (CYP-720).
 */
data class MigratableStore(
    val storeKey: String,
    val displayName: String,
    val state: StoreBindingState,
    val dsnLabel: String? = null,
    val readOnlyReason: ReadOnlyReason? = null,
)

/** Why a store is NOT migratable (spec §2.2). The reason CODE comes from the backend — the UI never derives it. */
enum class UnavailableReason { NO_EXPORT, NO_TARGET, INFRA, PENDING_DECISION }

/** A non-migratable store row — **shown, not omitted** (spec §2.2): store name + reason, no action control. */
data class UnavailableStore(
    val storeKey: String,
    val displayName: String,
    val reason: UnavailableReason,
)

/**
 * The store inventory for a project (spec §2): two groups, **never a total**. There is no aggregate count /
 * percentage / "fully migrated" state — that would pre-empt the open §6.4 scope.
 */
data class MigrationStores(
    val migratable: List<MigratableStore>,
    val unavailable: List<UnavailableStore>,
)

/** The four forward phases (spec §5.1) — from `MigrationPhase` minus the exits (ROLLBACK/DECOMMISSION). */
enum class MigrationPhase { WINDOW, COPY, VERIFY, REBIND }

/** A phase's state (spec §5.2). "done" carries NO glyph — its proof value ([PhaseProgress.value]) replaces it. */
enum class PhaseState { PENDING, RUNNING, DONE, FAILED, SKIPPED }

/**
 * One phase's progress. [value] is the **proof value** that replaces the (deliberately absent) "done" glyph —
 * COPY: "2.341 Zeilen", VERIFY: "2.341 / 2.341 · Prüfsumme identisch", REBIND: the target DSN label. **`null` ≠
 * "0"**: if the backend supplies no value, the row stays value-LESS (label + marker), never a fabricated 0.
 */
data class PhaseProgress(
    val phase: MigrationPhase,
    val state: PhaseState,
    val value: String? = null,
)

/** A live/finished migration run for one store (spec §5). [slow] toggles the calm "still running" line (§5.3). */
data class MigrationRun(
    val storeKey: String,
    val phases: List<PhaseProgress>,
    val slow: Boolean = false,
)

/** The success receipt (spec §6.1) from `MigrationReceipt` — row count + target label. Secret-free. */
data class MigrationReceipt(
    val rows: Long,
    val dsnLabel: String,
)

/**
 * A migration failure (spec §7). `StoreMigrator` catches every `Throwable`, `unbind()`s and rethrows → there is
 * no "half-migrated" state; every message ends "Nichts umgestellt." The variants map 1:1 to the §7 rows.
 */
sealed interface MigrationError {
    /** Row count short of the source (spec §7 row 1). */
    data class Verify(val sourceRows: Long, val targetRows: Long) : MigrationError

    /** Same row count, different checksum (spec §7 row 2). */
    data object Checksum : MigrationError

    /** Target/DSN unreachable (spec §7 row 3). */
    data object Connect : MigrationError

    /** Unknown — carries the backend's secret-free `MigrationAuditEntry.error` verbatim (spec §7 row 4). */
    data class Unknown(val message: String) : MigrationError
}

/** DSN provenance (spec §3, `DsnDescriptor.tierOrigin`). BYO changes who runs/backs-up the DB — shown, not hidden. */
enum class DsnOrigin { AIVEN_MANAGED, BYO }

/**
 * The target-DSN descriptor as the UI shows it (spec §3) — exactly the [com.tneff.*.DsnDescriptor] fields, no
 * invented/missing ones. The **password is never carried back**: [passwordMaskedLast4] is the server-masked
 * `***last4` form (the client never shortens), `null` when none is stored. [jdbcUrl] is credential-free.
 */
data class DsnView(
    val label: String,
    val host: String,
    val port: Int,
    val database: String,
    val user: String,
    val sslMode: String = "require",
    val origin: DsnOrigin = DsnOrigin.AIVEN_MANAGED,
    val passwordMaskedLast4: String? = null,
    val jdbcUrl: String? = null,
) {
    /** SSL is weakened when downgraded below `require`/`verify-*` — the §3 warning fires (no block). */
    val sslWeakened: Boolean get() = sslMode.lowercase().let { it != "require" && !it.startsWith("verify") }
}

/**
 * A history entry (spec §8) — mirrors the secret-free `MigrationAuditEntry`
 * (`ts, storeKey, phase, result, sourceRows, targetRows, checksumMatch, error, actor, toDsnId`).
 */
data class MigrationHistoryEntry(
    val ts: Long,
    val storeKey: String,
    val phase: String,
    val result: String,
    val sourceRows: Long? = null,
    val targetRows: Long? = null,
    val checksumMatch: Boolean? = null,
    val error: String? = null,
    val actor: String? = null,
    val toDsnId: String? = null,
)

/**
 * CYP-220 BE-1 — whether the audit sink is durably WIRED, a flag the server must supply (NOT derivable from an
 * empty list). [RECORDING] + empty ⇒ "no migration yet"; [UNAVAILABLE] ⇒ "history is not being recorded" — the
 * two DISTINCT empty states (spec §8). Absent this flag the honest choice is to not build §8 at all.
 */
enum class HistoryAvailability { RECORDING, UNAVAILABLE }

/** The history section's data (spec §8) — the availability flag drives the two distinct empty states. */
data class MigrationHistory(
    val availability: HistoryAvailability,
    val entries: List<MigrationHistoryEntry>,
)
