package com.tneff.cyppieagents.migration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.testing.enableTestTagsAsResourceId
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-220 §6.1 — the success receipt (the proof, H4): row count copied+verified + the target label, INFO (never
 * green), plus the H3 line that the previous data is untouched.
 */
@Composable
fun MigrationReceipt(receipt: MigrationReceipt, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TonedHint(
            text = stringResource(Res.string.migration_receipt_ok, receipt.rows.toString(), receipt.dsnLabel),
            tone = HintTone.INFO,
            tag = MigrationTags.RECEIPT,
        )
        Text(
            text = stringResource(Res.string.migration_receipt_source_kept),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().testTag(MigrationTags.RECEIPT_SOURCE_KEPT),
        )
    }
}

/**
 * §7 — a failure row. Every message ends "Nichts umgestellt." (backed by `StoreMigrator`'s catch→unbind). ERROR
 * tone, Assertive (spec §9) — a failure is the one place that interrupts.
 */
@Composable
fun MigrationErrorRow(error: MigrationError, modifier: Modifier = Modifier) {
    TonedHint(
        text = migrationErrorText(error),
        tone = HintTone.ERROR,
        tag = MigrationTags.ERROR,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Assertive },
    )
}

/**
 * §7 — rollback as an action (from READ_ONLY / a stuck window). NON-destructive color (nothing is lost); the
 * explain line is H3, placed exactly where the fear arises.
 */
@Composable
fun MigrationRollbackControl(onRollback: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = onRollback, enabled = enabled, modifier = Modifier.testTag(MigrationTags.ROLLBACK)) {
            Text(stringResource(Res.string.migration_rollback))
        }
        Text(
            text = stringResource(Res.string.migration_rollback_explain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().testTag(MigrationTags.ROLLBACK_EXPLAIN),
        )
    }
}

/**
 * §6.2 — decommission: delete the OLD source. The **only** destructive action in the flow (the migrator never
 * deletes the source, `StoreMigrator.kt:38-39`). Visible only when the binding is ACTIVE and the source still
 * exists (caller-gated). The trigger + its confirm dialog are the ONLY place with error color and the ONLY place
 * "kein Zurück" is stated — and it is true.
 */
@Composable
fun MigrationDecommissionControl(onDecommission: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Button(
        onClick = onDecommission,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
        modifier = modifier.testTag(MigrationTags.DECOMMISSION),
    ) { Text(stringResource(Res.string.migration_decommission)) }
}

/** §6.2 — the decommission confirm dialog (error-colored, own window → re-apply enableTestTagsAsResourceId). */
@Composable
fun MigrationDecommissionDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.enableTestTagsAsResourceId().testTag(MigrationTags.DECOMMISSION_DIALOG),
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.testTag(MigrationTags.DECOMMISSION_CONFIRM),
            ) { Text(stringResource(Res.string.migration_decommission_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag(MigrationTags.DECOMMISSION_CANCEL)) {
                Text(stringResource(Res.string.project_cancel))
            }
        },
        title = { Text(stringResource(Res.string.migration_decommission)) },
        text = {
            // The ONE place "kein Zurück" is stated — and it is true (this deletes the source).
            TonedHint(
                text = stringResource(Res.string.migration_decommission_warning),
                tone = HintTone.ERROR,
                tag = MigrationTags.DECOMMISSION_DIALOG + ".warning",
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}
