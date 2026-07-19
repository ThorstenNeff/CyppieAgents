package com.tneff.cyppieagents.migration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.eventlog.severityColor
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.LoadErrorRetry
import com.tneff.cyppieagents.ui.TonedHint
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-220 §1–§2 — the Migrations-Screen section: the **anti-lie surface**. A project-bound settings section
 * (§1.2) that shows every store as **one row in one of two groups** — migratable and NOT migratable — and
 * **never a total** (§2.3): no aggregate count, no percentage, no "fully migrated" state (that would pre-empt
 * the open §6.4 scope). Non-migratable stores are **shown with a reason**, not omitted — an omission would be a
 * false claim about the inventory (§2.2).
 *
 * **Gate (§1):** the stricter [com.tneff.cyppieagents.workspace.showRoster] form (true tier == OPERATOR), NOT
 * break-glass — the backend route is tier-gated, so a surface that *looks operable* and then 403s is a lie about
 * its own effectiveness. Non-operator ⇒ the section is **present, controls disabled**, + a GATED hint (observable,
 * never vanishing).
 */
@Composable
fun MigrationSection(
    state: MigrationUiState,
    isOperator: Boolean,
    activeProjectName: String,
    onStartMigrate: (storeKey: String) -> Unit = {},
    onReload: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().testTag(MigrationTags.SECTION),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.migration_section),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        // §1.2 — always show the active project; a migration is per-(store, project), never global.
        Text(
            text = stringResource(Res.string.migration_section_project, activeProjectName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(MigrationTags.PROJECT_LABEL),
        )

        if (!isOperator) {
            // §1 — the gate stays observable: present, disabled, explained. It never disappears.
            TonedHint(
                text = stringResource(Res.string.workspace_operator_only),
                tone = HintTone.GATED,
                tag = MigrationTags.GATE_HINT,
            )
        }

        // §2 (CYP-727) — inventory precedence: loading → error → empty → content (the CYP-288 house rule). A failed
        // query is NEVER rendered as an empty one; the three states stay visibly distinct on the anti-lie surface.
        when (val inventory = state.inventory) {
            InventoryState.Checking -> InventoryChecking()
            InventoryState.Unavailable -> LoadErrorRetry(
                message = stringResource(Res.string.migration_inventory_unavailable),
                onRetry = onReload,
                containerTag = MigrationTags.INVENTORY_UNAVAILABLE,
                retryTag = MigrationTags.INVENTORY_RETRY,
            )
            is InventoryState.Known -> {
                val stores = inventory.stores
                if (stores.migratable.isEmpty() && stores.unavailable.isEmpty()) {
                    // Genuinely empty (loaded, no stores) — an honest own line, NOT the silent header-only render
                    // the old `if (stores != null)` produced (which showed nothing at all for an empty inventory).
                    StoresEmpty()
                } else {
                    if (stores.migratable.isNotEmpty()) {
                        GroupHeading(stringResource(Res.string.migration_group_migratable), MigrationTags.GROUP_MIGRATABLE)
                        for (store in stores.migratable) MigratableRow(store, isOperator, onStartMigrate)
                    }
                    if (stores.unavailable.isNotEmpty()) {
                        GroupHeading(stringResource(Res.string.migration_group_unavailable), MigrationTags.GROUP_UNAVAILABLE)
                        for (store in stores.unavailable) UnavailableRow(store)
                    }
                }
            }
        }
    }
}

/**
 * CYP-727 §2 — the inventory query is in flight. A spinner + a text label (never a bare, SR-mute spinner) with a
 * Polite live region so the checking state is announced. This is the "loading" tier of loading→error→empty.
 */
@Composable
private fun InventoryChecking() {
    val checking = stringResource(Res.string.migration_checking)
    Row(
        modifier = Modifier.testTag(MigrationTags.CHECKING).semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = checking
        },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(checking, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * CYP-727 §2 — the inventory loaded and is genuinely empty (both groups empty). An explicit "nothing to migrate"
 * line — true ONLY here, never on a failed load (that path renders [LoadErrorRetry] instead, higher precedence).
 */
@Composable
private fun StoresEmpty() {
    Text(
        text = stringResource(Res.string.migration_stores_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(MigrationTags.STORES_EMPTY),
    )
}

@Composable
private fun GroupHeading(text: String, tag: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag(tag).semantics { heading() },
    )
}

/**
 * A migratable store row (§2.1): name · state · action. CYP-730 §2.2b: a LEGACY_UNEVALUATED READ_ONLY store carries
 * an action line UNDER the row (it needs a review before it can migrate), so the row is a Column wrapping the
 * name/state/action Row plus the optional line.
 */
@Composable
private fun MigratableRow(store: MigratableStore, isOperator: Boolean, onStartMigrate: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(MigrationTags.store(store.storeKey)),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = store.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            StoreState(store)
            // Action — start a migration. Enabled only for an operator and only when the store is LOCAL (unbound);
            // MIGRATING/READ_ONLY are in-flight, BOUND is already on a target. §4's confirm dialog is a later increment.
            val canMigrate = isOperator && store.state == StoreBindingState.LOCAL
            OutlinedButton(
                onClick = { onStartMigrate(store.storeKey) },
                enabled = canMigrate,
                modifier = Modifier.testTag(MigrationTags.storeMigrate(store.storeKey)),
            ) { Text(stringResource(Res.string.migration_start)) }
        }
        // CYP-730 §2.2b — the LEGACY_UNEVALUATED action line: this store was frozen fail-closed (CYP-714) because
        // its binding predates state-evaluation; writes stay blocked NOW until an operator reviews it (not a
        // migration precondition — the freeze is active regardless of whether anyone intends to migrate).
        if (store.state == StoreBindingState.READ_ONLY && store.readOnlyReason == ReadOnlyReason.LEGACY_UNEVALUATED) {
            Text(
                text = stringResource(Res.string.migration_legacy_action),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(MigrationTags.storeLegacyAction(store.storeKey)),
            )
        }
    }
}

/** The state cell (§2.1). Qualifier tag `.local/.bound/.migrating/.readonly`; never colour-alone (WCAG 1.4.1). */
@Composable
private fun StoreState(store: MigratableStore) {
    val qualifier = when (store.state) {
        StoreBindingState.LOCAL -> "local"
        StoreBindingState.BOUND -> "bound"
        StoreBindingState.MIGRATING -> "migrating"
        StoreBindingState.READ_ONLY -> "readonly"
    }
    val tag = "${MigrationTags.storeState(store.storeKey)}.$qualifier"
    when (store.state) {
        StoreBindingState.LOCAL -> Text(
            text = stringResource(Res.string.migration_state_local),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(tag),
        )
        StoreBindingState.BOUND -> Text(
            text = stringResource(Res.string.migration_state_bound, store.dsnLabel ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(tag),
        )
        StoreBindingState.MIGRATING -> {
            val running = stringResource(Res.string.migration_state_migrating)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                // Polite live region + a text label so the bare spinner is never the sole (SR-mute) signal.
                modifier = Modifier.testTag(tag).semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = running
                },
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(running, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
        // CYP-730 §2.2b — READ_ONLY splits by provenance into three distinct rows (see [ReadOnlyState]).
        StoreBindingState.READ_ONLY -> ReadOnlyState(store)
    }
}

/**
 * CYP-730 §2.2b — the READ_ONLY state cell, split by provenance into three DISTINCT rows:
 *  - [ReadOnlyReason.MIGRATION_WINDOW]   → NOT a warning: `secondary` colour, no ▲ (an active migration, not a fault).
 *  - [ReadOnlyReason.LEGACY_UNEVALUATED] → WARN + ▲ (frozen, needs evaluation; carries an action line in [MigratableRow]).
 *  - `null` (reason absent)              → WARN + ▲ (unknown provenance; fail-loud, never silently "fine").
 *
 * The a11y [androidx.compose.ui.semantics.stateDescription] carries a **localized** reason label (BE-4), never the
 * raw enum name — a screenreader must not read "Nur lesend (LEGACY_UNEVALUATED)". Colour is never the sole signal:
 * WARN reasons carry a separate ▲ node (WCAG 1.4.1).
 */
@Composable
private fun ReadOnlyState(store: MigratableStore) {
    val reason = store.readOnlyReason
    val readonly = stringResource(Res.string.migration_state_readonly)
    val reasonLabel = when (reason) {
        ReadOnlyReason.MIGRATION_WINDOW -> stringResource(Res.string.migration_readonly_reason_migrating)
        ReadOnlyReason.LEGACY_UNEVALUATED -> stringResource(Res.string.migration_readonly_reason_legacy)
        null -> stringResource(Res.string.migration_readonly_reason_unknown)
    }
    val warn = readOnlyIsWarn(reason)
    val color = if (warn) severityColor(Severity.WARN) else MaterialTheme.colorScheme.secondary
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.testTag(MigrationTags.storeReadonly(store.storeKey, reason))
            .semantics { stateDescription = "$readonly — $reasonLabel" },
    ) {
        // MIGRATION_WINDOW is not a warning → no ▲, secondary colour. Legacy/unknown keep the ▲ WARN form marker.
        if (warn) Text("▲", color = color, style = MaterialTheme.typography.bodySmall)
        Text(readonly, color = color, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * CYP-730 — READ_ONLY warning classification: an in-flight migration ([ReadOnlyReason.MIGRATION_WINDOW]) is NOT a
 * warning (no amber, else the genuinely-worrying legacy/unknown rows stop standing out); every other reason is.
 */
internal fun readOnlyIsWarn(reason: ReadOnlyReason?): Boolean = reason != ReadOnlyReason.MIGRATION_WINDOW

/** A non-migratable store row (§2.2): name + reason, no action control (there is no action). */
@Composable
private fun UnavailableRow(store: UnavailableStore) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(MigrationTags.unavailable(store.storeKey)),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = store.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        TonedHint(
            text = stringResource(unavailableReasonKey(store.reason)),
            tone = HintTone.GATED,
            tag = MigrationTags.unavailableReason(store.storeKey),
        )
    }
}

private fun unavailableReasonKey(reason: UnavailableReason) = when (reason) {
    UnavailableReason.NO_EXPORT -> Res.string.migration_reason_no_export
    UnavailableReason.NO_TARGET -> Res.string.migration_reason_no_target
    UnavailableReason.INFRA -> Res.string.migration_reason_infra
    UnavailableReason.PENDING_DECISION -> Res.string.migration_reason_pending_decision
}
