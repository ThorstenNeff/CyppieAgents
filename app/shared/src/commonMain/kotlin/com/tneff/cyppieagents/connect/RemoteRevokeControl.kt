package com.tneff.cyppieagents.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.testing.enableTestTagsAsResourceId
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_remote_revoke_ended
import kmpcyppieagents.app.shared.generated.resources.agent_cancel
import kmpcyppieagents.app.shared.generated.resources.remote_revoke_confirm_body
import kmpcyppieagents.app.shared.generated.resources.remote_revoke_confirm_title
import kmpcyppieagents.app.shared.generated.resources.remote_revoke_end_action
import kmpcyppieagents.app.shared.generated.resources.remote_revoke_ended
import kmpcyppieagents.app.shared.generated.resources.remote_revoke_scope_note
import kmpcyppieagents.app.shared.generated.resources.remote_revoke_ttl_hint
import org.jetbrains.compose.resources.stringResource

private enum class RevokePhase { IDLE, CONFIRMING, ENDED }

/**
 * CYP-479 §4.1 — the "End remote session" control (CYP-480 Fläche ③). Honesty (HF, guaranteed-vs-advisory):
 * confirming triggers a **guaranteed, immediate local teardown** of THIS connection ([onEndSession] — the live
 * mount wires it to `HubConnectViewModel.backToHubList()` / `RemoteHubSession.close()`); the **scope note**
 * carries the honest "only this connection on this device — no global revoke" truth; the **≤TTL hint** is
 * **seam-gated** ([ttl], default `null`) — rendered only when the backend supplies an Operator-Session-TTL,
 * never an invented expiry (③a, `null≠0`). The confirm follows the house destructive-confirm scaffold
 * (`LockoutDialog`/`RemoveDialog`); the control itself is neutral (no scare-red).
 *
 * Live mount = the remote-operating context banner (`WorkspaceTags.ROLE_INDICATOR`) / the `RemoteConnectingView`
 * CONNECTED row — rides with the RR5 transport (a real live session). Standalone + render-tested here.
 */
@Composable
fun RemoteRevokeControl(
    onEndSession: () -> Unit,
    modifier: Modifier = Modifier,
    ttl: String? = null,
) {
    var phase by remember { mutableStateOf(RevokePhase.IDLE) }

    if (phase == RevokePhase.ENDED) {
        val endedA11y = stringResource(Res.string.a11y_remote_revoke_ended)
        Text(
            stringResource(Res.string.remote_revoke_ended),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.testTag(RemoteRevokeTags.ENDED).semantics { contentDescription = endedA11y },
        )
        return
    }

    TextButton(onClick = { phase = RevokePhase.CONFIRMING }, modifier = modifier.testTag(RemoteRevokeTags.END)) {
        Text(stringResource(Res.string.remote_revoke_end_action))
    }

    if (phase == RevokePhase.CONFIRMING) {
        AlertDialog(
            onDismissRequest = { phase = RevokePhase.IDLE },
            // The dialog renders in its own Compose window (doesn't inherit the root's tag→resource-id) → re-apply.
            modifier = Modifier.enableTestTagsAsResourceId(),
            title = { Text(stringResource(Res.string.remote_revoke_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Guaranteed: this connection is torn down immediately.
                    Text(stringResource(Res.string.remote_revoke_confirm_body))
                    // Advisory (HF): no global revoke.
                    Text(
                        stringResource(Res.string.remote_revoke_scope_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(RemoteRevokeTags.SCOPE_NOTE),
                    )
                    // Seam-gated: ≤TTL hint only when the backend supplies an Operator-Session-TTL (else absent).
                    if (ttl != null) {
                        Text(
                            stringResource(Res.string.remote_revoke_ttl_hint, ttl),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag(RemoteRevokeTags.TTL_HINT),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    // The tappable destructive action carries `remote.revoke.confirm` (the frozen contract has no
                    // separate confirm-button vs dialog-container tag; the button's text collides with the trigger).
                    onClick = { phase = RevokePhase.ENDED; onEndSession() },
                    modifier = Modifier.testTag(RemoteRevokeTags.CONFIRM),
                ) { Text(stringResource(Res.string.remote_revoke_end_action)) }
            },
            dismissButton = {
                TextButton(onClick = { phase = RevokePhase.IDLE }) { Text(stringResource(Res.string.agent_cancel)) }
            },
        )
    }
}
