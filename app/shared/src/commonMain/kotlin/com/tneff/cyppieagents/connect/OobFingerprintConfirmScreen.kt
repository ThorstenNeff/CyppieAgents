package com.tneff.cyppieagents.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.net.hub.trust.HubFingerprintDisplay
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.remote_connect_trust_aborted
import kmpcyppieagents.app.shared.generated.resources.remote_connect_trust_confirm
import kmpcyppieagents.app.shared.generated.resources.remote_connect_trust_first_body
import kmpcyppieagents.app.shared.generated.resources.remote_connect_trust_first_title
import kmpcyppieagents.app.shared.generated.resources.remote_connect_trust_hex_label
import kmpcyppieagents.app.shared.generated.resources.remote_connect_trust_oob
import kmpcyppieagents.app.shared.generated.resources.remote_connect_trust_oob_console
import kmpcyppieagents.app.shared.generated.resources.remote_connect_trust_provisional
import kmpcyppieagents.app.shared.generated.resources.remote_connect_trust_qr_label
import kmpcyppieagents.app.shared.generated.resources.remote_connect_trust_reject
import kmpcyppieagents.app.shared.generated.resources.remote_connect_trust_wordlist_label
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-482 S-B §3 — the **FirstUse OOB-fingerprint-confirm screen** (renders `OobConfirmState.Awaiting`; the
 * state/logic is CYP-478, the visual is this). It **blocks** the connect progression (HA, mandatory): the ONLY
 * exits are **confirm** (→ `PendingOobConfirmations.approve()` → pin → AUTHENTICATING) and **reject** (→
 * `reject()` → fail-closed abort → [TrustAbortedView]). There is **no** skip / later / dismiss-without-decision.
 *
 * The fingerprint is **always real-derived** ([HubFingerprintDisplay] over the hub's [hubDhPubKey], HB): the PGP
 * word list (11 numbered even/odd tokens, primary OOB compare) + hex (copyable) + QR payload. No placeholder —
 * the caller only reaches this with real `dhPubKey` bytes; while the live feed isn't running the
 * [provisional] disclosure stays (Q3, §5). Copy/tags = the frozen CYP-480 §① `remote_connect_trust_*` contract
 * (0 net-new). Reject is neutral (the safe choice, HC — no scare-red).
 */
@Composable
fun OobFingerprintConfirmScreen(
    hubName: String,
    hubDhPubKey: ByteArray,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
    provisional: Boolean = true,
) {
    val words = HubFingerprintDisplay.words(hubDhPubKey) // 11 even/odd tokens (88 bit)
    val hex = HubFingerprintDisplay.hex(hubDhPubKey)
    val qrPayload = HubFingerprintDisplay.qrPayload(hubDhPubKey)
    val clipboard = LocalClipboardManager.current
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier.fillMaxWidth().testTag(RemoteConnectTags.TRUST_FIRST),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.remote_connect_trust_first_title, hubName), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(Res.string.remote_connect_trust_first_body), style = MaterialTheme.typography.bodyMedium)

        // Fingerprint block — REAL-derived (HB), never a placeholder.
        Column(Modifier.testTag(RemoteConnectTags.TRUST_FINGERPRINT), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Primary: PGP word list, 11 NUMBERED tokens (position compare; even/odd catches transposition).
            Text(stringResource(Res.string.remote_connect_trust_wordlist_label), style = MaterialTheme.typography.labelMedium, color = onSurfaceVariant)
            Column(Modifier.testTag(RemoteConnectTags.TRUST_WORDLIST)) {
                words.forEachIndexed { i, w -> Text("${i + 1}. $w", style = MaterialTheme.typography.bodyMedium) }
            }
            // Secondary: hex (copyable).
            Text(stringResource(Res.string.remote_connect_trust_hex_label), style = MaterialTheme.typography.labelMedium, color = onSurfaceVariant)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                SelectionContainer { Text(hex, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(RemoteConnectTags.TRUST_HEX)) }
                TextButton(onClick = { clipboard.setText(AnnotatedString(hex)) }) {
                    Text("⧉", style = MaterialTheme.typography.titleSmall, color = onSurfaceVariant)
                }
            }
            // Secondary: QR payload (scan path, `cyppie-hub-key:` scheme; bitmap rendering is a later nicety).
            Text(stringResource(Res.string.remote_connect_trust_qr_label), style = MaterialTheme.typography.labelMedium, color = onSurfaceVariant)
            SelectionContainer { Text(qrPayload, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(RemoteConnectTags.TRUST_QR)) }
        }

        // OOB instruction (Option X — compare against the hub console).
        Text(stringResource(Res.string.remote_connect_trust_oob), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(RemoteConnectTags.TRUST_OOB))
        Text(stringResource(Res.string.remote_connect_trust_oob_console), style = MaterialTheme.typography.bodySmall, color = onSurfaceVariant, modifier = Modifier.testTag(RemoteConnectTags.TRUST_OOB_CONSOLE))

        // Provisional disclosure (§5/Q3) — stays while the live feed / real pinning isn't running.
        if (provisional) {
            Text(stringResource(Res.string.remote_connect_trust_provisional), style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant, modifier = Modifier.testTag(RemoteConnectTags.TRUST_PROVISIONAL))
        }

        // The ONLY two exits (HA — no skip/third exit). Confirm = neutral primary; reject = neutral (safe choice, HC).
        Button(onClick = onConfirm, modifier = Modifier.testTag(RemoteConnectTags.TRUST_CONFIRM)) {
            Text(stringResource(Res.string.remote_connect_trust_confirm))
        }
        TextButton(onClick = onReject, modifier = Modifier.testTag(RemoteConnectTags.TRUST_REJECT)) {
            Text(stringResource(Res.string.remote_connect_trust_reject))
        }
    }
}

/**
 * CYP-482 S-B §3 — the honest **Rejected** result (`OobConfirmState.Rejected`): fingerprint mismatch → aborted,
 * nothing pinned, not connected. Neutral (the reject was the safe choice, HC) — never scare-red.
 */
@Composable
fun TrustAbortedView(modifier: Modifier = Modifier) {
    Text(
        stringResource(Res.string.remote_connect_trust_aborted),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.testTag(RemoteConnectTags.TRUST_ABORTED),
    )
}
