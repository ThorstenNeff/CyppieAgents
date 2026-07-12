package com.tneff.cyppieagents.net.hub.operator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.remote_recovery_code_label
import kmpcyppieagents.app.shared.generated.resources.remote_recovery_exhausted
import kmpcyppieagents.app.shared.generated.resources.remote_recovery_invalid_code
import kmpcyppieagents.app.shared.generated.resources.remote_recovery_multidevice_hint
import kmpcyppieagents.app.shared.generated.resources.remote_recovery_no_central
import kmpcyppieagents.app.shared.generated.resources.remote_recovery_start_body
import kmpcyppieagents.app.shared.generated.resources.remote_recovery_start_title
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-479 §3.2 — the recovery-input content (lost device → enter a backup code → re-enroll = re-pin). Content
 * composable driven by [RemoteRecoveryViewModel.State]; submit rides the field's IME-Done (the enclosing dialog
 * owns any chrome — the CYP-460 idiom). Honesty:
 *  - [remote_recovery_no_central] is **always** shown (HE/Q6: a central login alone does not restore access).
 *  - the multi-device recommendation is **seam-gated** ([multiDeviceAvailable], default `false`) — rendered
 *    only once the server multi-device-enroll exists (②a); until then it is **absent** (`null≠0`), never a
 *    hint the operator can't yet follow.
 *  - a wrong/used code is a **retryable** error (error tone, field stays usable); **exhausted** is terminal +
 *    **neutral** (fail-closed OOB-at-hub redirect, no alarm) and disables the field.
 */
@Composable
fun RecoveryInputContent(
    state: RemoteRecoveryViewModel.State,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    multiDeviceAvailable: Boolean = false,
) {
    val exhausted = state.error == RecoveryError.Exhausted
    Column(
        modifier = modifier.fillMaxWidth().testTag(RemoteRecoveryTags.START),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(Res.string.remote_recovery_start_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(Res.string.remote_recovery_start_body), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = state.code,
            onValueChange = onCodeChange,
            enabled = !state.submitting && !exhausted, // fail-closed: no more tries once exhausted
            singleLine = true,
            label = { Text(stringResource(Res.string.remote_recovery_code_label)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier.fillMaxWidth().testTag(RemoteRecoveryTags.CODE_FIELD),
        )
        // HE: no central-login recovery — always shown.
        Text(
            stringResource(Res.string.remote_recovery_no_central),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(RemoteRecoveryTags.NO_CENTRAL),
        )
        // Seam-gated: the multi-device recommendation only when the server supports it (currently never).
        if (multiDeviceAvailable) {
            Text(
                stringResource(Res.string.remote_recovery_multidevice_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(RemoteRecoveryTags.MULTIDEVICE),
            )
        }
        when (state.error) {
            RecoveryError.InvalidCode ->
                TonedHint(
                    stringResource(Res.string.remote_recovery_invalid_code),
                    HintTone.ERROR,
                    RemoteRecoveryTags.ERROR,
                )
            RecoveryError.Exhausted ->
                Text( // terminal but NEUTRAL — honest OOB-at-hub redirect, no alarm
                    stringResource(Res.string.remote_recovery_exhausted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(RemoteRecoveryTags.ERROR),
                )
            null -> {}
        }
    }
}
