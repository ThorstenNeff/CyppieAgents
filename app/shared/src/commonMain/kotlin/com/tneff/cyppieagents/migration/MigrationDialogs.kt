package com.tneff.cyppieagents.migration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.testing.enableTestTagsAsResourceId
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-220 §4 — the start-confirm dialog + the migration-window disclosures (H2, the write-freeze — the spec's own
 * finding, the most-felt user effect). The dialog is the house `DeleteDialog` form but **NOT destructive** — a
 * migration is not a delete; the confirm is a normal [Button], never error-colored (destructive color is reserved
 * for §6.2 decommission). ⚠ the dialog renders in its OWN Compose window which does NOT inherit the root's
 * `enableTestTagsAsResourceId()` → it is re-applied here, else all §4/§6.2 tags are invisible to Maestro.
 */
@Composable
fun MigrationConfirmDialog(
    storeName: String,
    targetLabel: String,
    error: MigrationError? = null,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.enableTestTagsAsResourceId().testTag(MigrationTags.CONFIRM_DIALOG),
        confirmButton = {
            // A normal Button — a migration is NOT a delete (spec §4.1). Destructive color is §6.2 only.
            Button(onClick = onConfirm, modifier = Modifier.testTag(MigrationTags.CONFIRM_START)) {
                Text(stringResource(Res.string.migration_confirm_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag(MigrationTags.CONFIRM_CANCEL)) {
                Text(stringResource(Res.string.project_cancel)) // reuse — no third "cancel" vocabulary (keys §Reuse)
            }
        },
        title = { Text(stringResource(Res.string.migration_confirm_title, storeName, targetLabel)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // H2 as the MAIN statement — EFFECT_DEFERRED (the only banner tone): writes are rejected, reads run.
                TonedHint(
                    text = stringResource(Res.string.migration_confirm_freeze),
                    tone = HintTone.EFFECT_DEFERRED,
                    tag = MigrationTags.CONFIRM_FREEZE,
                )
                // H3 reassurance, and TRUE (StoreMigrator never deletes the source): the data is kept, even on abort.
                Text(
                    text = stringResource(Res.string.migration_confirm_source_kept),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(MigrationTags.CONFIRM_SOURCE_KEPT),
                )
                // Error slot.
                if (error != null) {
                    TonedHint(text = migrationErrorText(error), tone = HintTone.ERROR, tag = MigrationTags.ERROR)
                }
            }
        },
    )
}

/**
 * §4.2 — the standing window disclosure while a store is MIGRATING. Not only in the dialog: the dialog closes, the
 * window is still open, so this line persists next to the store. EFFECT_DEFERRED (`!` banner) — deferred, not error.
 */
@Composable
fun MigrationWindowBanner(storeName: String, modifier: Modifier = Modifier) {
    TonedHint(
        text = stringResource(Res.string.migration_window_active, storeName),
        tone = HintTone.EFFECT_DEFERRED,
        tag = MigrationTags.WINDOW_ACTIVE,
        modifier = modifier,
    )
}

/**
 * §4.2 — the app-wide copy for a `store_migrating` 409 anywhere else in the app. Tone **EFFECT_DEFERRED, NOT
 * ERROR**: nothing is broken, the action is deferred and the retry path is real. ⟂ the client matches on the typed
 * code `store_migrating` (`MigrationGate.kt:30` → `ApiException` envelope `{error:{code}}`), never on the text.
 */
@Composable
fun MigrationWriteRejectedHint(modifier: Modifier = Modifier) {
    TonedHint(
        text = stringResource(Res.string.migration_write_rejected),
        tone = HintTone.EFFECT_DEFERRED,
        tag = MigrationTags.WRITE_REJECTED,
        modifier = modifier,
    )
}

/**
 * §7 — the failure text; every message ends "Nichts umgestellt." (backed by `StoreMigrator`'s catch→`unbind()`).
 * Shared by the §4 dialog error slot and the §7 error row. The [MigrationError.Unknown] message is the
 * secret-free `MigrationAuditEntry.error`, passed through verbatim.
 */
@Composable
fun migrationErrorText(error: MigrationError): String = when (error) {
    is MigrationError.Verify ->
        stringResource(Res.string.migration_error_verify, error.targetRows.toString(), error.sourceRows.toString())
    MigrationError.Checksum -> stringResource(Res.string.migration_error_checksum)
    MigrationError.Connect -> stringResource(Res.string.migration_error_connect)
    is MigrationError.Unknown -> stringResource(Res.string.migration_error_unknown, error.message)
}
