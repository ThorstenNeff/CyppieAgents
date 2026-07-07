package com.tneff.cyppieagents.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_load_error
import kmpcyppieagents.app.shared.generated.resources.load_retry
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-288 — the ONE shared "a load failed, try again" surface, reused across every REST-backed panel so a
 * failed load is honestly distinct from a genuinely-empty result (never a failed-state-rendered-as-empty, the
 * Sweep-#4 class-A honesty gap). A ⚠ form-marker in [MaterialTheme.colorScheme.error] + a neutral [message] in
 * `onSurface` + a ≥48dp Retry button. Uses ONLY scheme roles (no hardcoded colors) so the maritime theme maps
 * it automatically; carries an a11y label so the whole surface announces as an error, not just decoration.
 *
 * Render precedence is the invariant: loading → **error → empty** → content — the error surface ALWAYS beats
 * the empty state. [message] is the panel's copy (systemic default: `load_failed`); [onRetry] MUST re-invoke
 * the panel's load.
 */
@Composable
fun LoadErrorRetry(
    message: String,
    onRetry: () -> Unit,
    containerTag: String,
    retryTag: String,
    modifier: Modifier = Modifier,
) {
    val a11y = stringResource(Res.string.a11y_load_error)
    Column(
        modifier = modifier.padding(12.dp).testTag(containerTag).semantics { contentDescription = a11y },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            // ⚠ is the only error-colored element (form marker); the message stays neutral onSurface.
            Text("⚠", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Text(message, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            onClick = onRetry,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag(retryTag),
        ) { Text(stringResource(Res.string.load_retry)) }
    }
}
