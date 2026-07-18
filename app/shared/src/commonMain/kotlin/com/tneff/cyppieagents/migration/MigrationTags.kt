package com.tneff.cyppieagents.migration

/**
 * CYP-220 — `testTag` contract for the operator Migrations-Screen, exactly per
 * `docs/design/CYP-220-migration-ui-tags.md`. Test-Contract v0.5 §2: prefixless
 * `<area>[.<scopeId>].<element>[.<qualifier>]`, segment values `[A-Za-z0-9-]+` (camelCase, no dots,
 * no underscores). Area `migration`, single-instance. **Shared API with QA (CYP-7) — additive, never
 * rename silently; coordinate via the PO.**
 *
 * ⚠ **`storeKey` schema collision (tags §0):** the real `storeKey`s are snake_case (`remote_token`, …), but a
 * tag segment may not contain `_`. [seg] maps the storeKey to a lossless camelCase segment for the TAG ONLY —
 * the display value and the backend identity stay snake_case. Anyone building a store tag MUST go through [seg].
 */
object MigrationTags {
    const val AREA = "migration"

    // §1 — Section & gate
    const val SECTION = "migration.section"
    const val GATE_HINT = "migration.gateHint"
    const val PROJECT_LABEL = "migration.projectLabel"

    // §2 — Scope (the anti-lie surface)
    const val GROUP_MIGRATABLE = "migration.group.migratable"
    const val GROUP_UNAVAILABLE = "migration.group.unavailable"
    fun store(storeKey: String) = "migration.store.${seg(storeKey)}"
    fun storeState(storeKey: String) = "migration.store.${seg(storeKey)}.state"
    fun storeMigrate(storeKey: String) = "migration.store.${seg(storeKey)}.migrate"
    fun unavailable(storeKey: String) = "migration.unavailable.${seg(storeKey)}"
    fun unavailableReason(storeKey: String) = "migration.unavailable.${seg(storeKey)}.reason"

    // §3 — Target DSN
    const val DSN_SECTION = "migration.dsn.section"
    const val DSN_LABEL = "migration.dsn.label"
    const val DSN_HOST = "migration.dsn.host"
    const val DSN_PORT = "migration.dsn.port"
    const val DSN_DATABASE = "migration.dsn.database"
    const val DSN_USER = "migration.dsn.user"
    const val DSN_SSLMODE = "migration.dsn.sslMode"
    const val DSN_SSL_WARNING = "migration.dsn.sslWarning"
    const val DSN_PASSWORD = "migration.dsn.password"
    const val DSN_PASSWORD_MASKED = "migration.dsn.passwordMasked"
    const val DSN_PASSWORD_REVEAL = "migration.dsn.passwordReveal"
    const val DSN_ORIGIN = "migration.dsn.origin"

    // §4 — Start & window
    const val START = "migration.start"
    const val CONFIRM_DIALOG = "migration.confirmDialog"
    const val CONFIRM_FREEZE = "migration.confirmDialog.freeze"
    const val CONFIRM_SOURCE_KEPT = "migration.confirmDialog.sourceKept"
    const val CONFIRM_START = "migration.confirmDialog.confirm"
    const val CONFIRM_CANCEL = "migration.confirmDialog.cancel"
    const val WINDOW_ACTIVE = "migration.windowActive"
    const val WRITE_REJECTED = "migration.writeRejected"

    // §5 — Phases
    const val PHASES = "migration.phases"
    fun phase(p: String) = "migration.phase.$p"
    fun phaseValue(p: String) = "migration.phase.$p.value"
    const val SLOW = "migration.slow"

    // §6–§7 — Result, rollback, decommission
    const val RECEIPT = "migration.receipt"
    const val RECEIPT_SOURCE_KEPT = "migration.receipt.sourceKept"
    const val ERROR = "migration.error"
    const val ROLLBACK = "migration.rollback"
    const val ROLLBACK_EXPLAIN = "migration.rollbackExplain"
    const val DECOMMISSION = "migration.decommission"
    const val DECOMMISSION_DIALOG = "migration.decommissionDialog"
    const val DECOMMISSION_CONFIRM = "migration.decommissionDialog.confirm"
    const val DECOMMISSION_CANCEL = "migration.decommissionDialog.cancel"

    // §8 — History (the two DISTINCT empty states)
    const val HISTORY = "migration.history"
    const val HISTORY_EMPTY = "migration.history.empty"
    const val HISTORY_UNAVAILABLE = "migration.history.unavailable"
    fun historyRow(i: Int) = "migration.history.$i"

    /**
     * `storeKey` (snake_case, backend truth) → tag segment (camelCase, schema-conforming). Lossless — no
     * storeKey contains uppercase or hyphens, so the mapping is uniquely reversible (tags §0).
     */
    fun seg(storeKey: String): String =
        storeKey.split('_').mapIndexed { i, p -> if (i == 0) p else p.replaceFirstChar(Char::uppercase) }.joinToString("")
}
