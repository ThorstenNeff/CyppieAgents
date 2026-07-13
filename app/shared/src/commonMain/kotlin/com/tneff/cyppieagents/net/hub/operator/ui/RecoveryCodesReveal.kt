package com.tneff.cyppieagents.net.hub.operator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_remote_recovery_codes
import kmpcyppieagents.app.shared.generated.resources.remote_recovery_codes_ack
import kmpcyppieagents.app.shared.generated.resources.remote_recovery_codes_body
import kmpcyppieagents.app.shared.generated.resources.remote_recovery_codes_copy
import kmpcyppieagents.app.shared.generated.resources.remote_recovery_codes_no_central
import kmpcyppieagents.app.shared.generated.resources.remote_recovery_codes_single_use
import kmpcyppieagents.app.shared.generated.resources.remote_recovery_codes_title
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-479 §3.1 — the **one-time** backup-codes reveal shown after a device enroll (hangs off the CYP-460
 * `OperatorAuthStep.Enroll` step; live mount rides with the server code-generation, CYP-459/469). Honesty (HD):
 * the codes are shown **once**, **readable and NOT masked** (they must be read + stored), inside a
 * [SelectionContainer] (manual-copy fallback, esp. Web); leaving is gated behind an explicit acknowledgement
 * ("I've saved the codes") — there is **no** "view codes again" affordance. Code count/format = a later
 * Reviewer threat-model AC; this renders whatever [codes] the server provides.
 */
@Composable
fun RecoveryCodesReveal(
    codes: List<String>,
    onAcknowledged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val a11y = stringResource(Res.string.a11y_remote_recovery_codes)
    Column(
        modifier = modifier.fillMaxWidth().testTag(RemoteRecoveryTags.CODES)
            .semantics { contentDescription = a11y },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(Res.string.remote_recovery_codes_title), style = MaterialTheme.typography.titleMedium)
        // Once-visible + single-use honesty — neutral, no alarm.
        Text(stringResource(Res.string.remote_recovery_codes_body), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(Res.string.remote_recovery_codes_single_use),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Readable (NOT masked) codes, selectable as a manual-copy fallback.
        SelectionContainer {
            Column(
                modifier = Modifier.fillMaxWidth().testTag(RemoteRecoveryTags.CODES_LIST),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                codes.forEach { code -> Text(code, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        TextButton(
            onClick = { clipboard.setText(AnnotatedString(codes.joinToString("\n"))) },
            modifier = Modifier.testTag(RemoteRecoveryTags.CODES_COPY),
        ) {
            Text(stringResource(Res.string.remote_recovery_codes_copy))
        }
        // CYP-525 GE7: the load-bearing consequence — no central login restores access, so these codes are the ONLY
        // way back — rendered ON the reveal, BEFORE the ack, so the user knows the stakes before quittancing (①).
        // Emphasized (onSurface, not the dimmed onSurfaceVariant) so it is actually read, not skimmed past.
        Text(
            stringResource(Res.string.remote_recovery_codes_no_central),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().testTag(RemoteRecoveryTags.CODES_NO_CENTRAL),
        )
        // Leave-gate (HD): the only way forward is the explicit "I've saved them" acknowledgement.
        Button(onClick = onAcknowledged, modifier = Modifier.testTag(RemoteRecoveryTags.CODES_ACK)) {
            Text(stringResource(Res.string.remote_recovery_codes_ack))
        }
    }
}
