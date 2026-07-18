package com.tneff.cyppieagents.migration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.eventlog.severityColor
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-220 §3 — the target-DSN input. Fields are **exactly** `DsnDescriptor` (`DsnRegistry.kt:24-33`) — none
 * invented, none missing. The **password is write-only** (house `ApiKeySection` pattern): masked while typing,
 * the reveal toggle un-masks only the CURRENT input, and the stored state shows the **server-masked** `***last4`
 * — the client never shortens, and there is no clear-text return path at all (`DsnRegistry.kt:42-49`). `sslMode`
 * defaults to `require`; downgrading it surfaces a WARN (no block). `tierOrigin` (managed vs BYO) is shown, because
 * BYO changes who runs the DB.
 */
@Composable
fun MigrationDsnSection(
    stored: DsnView?,
    editable: Boolean,
    onSave: (DsnDraft) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var label by remember { mutableStateOf(stored?.label ?: "") }
    var host by remember { mutableStateOf(stored?.host ?: "") }
    var port by remember { mutableStateOf(stored?.port?.toString() ?: "") }
    var database by remember { mutableStateOf(stored?.database ?: "") }
    var user by remember { mutableStateOf(stored?.user ?: "") }
    var sslMode by remember { mutableStateOf(stored?.sslMode ?: "require") }
    var password by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth().testTag(MigrationTags.DSN_SECTION),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.migration_dsn_section),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )

        DsnField(stringResource(Res.string.migration_dsn_field_label), label, editable, MigrationTags.DSN_LABEL) { label = it }
        DsnField(stringResource(Res.string.migration_dsn_field_host), host, editable, MigrationTags.DSN_HOST) { host = it }
        DsnField(stringResource(Res.string.migration_dsn_field_port), port, editable, MigrationTags.DSN_PORT) { port = it.filter(Char::isDigit) }
        DsnField(stringResource(Res.string.migration_dsn_field_database), database, editable, MigrationTags.DSN_DATABASE) { database = it }
        DsnField(stringResource(Res.string.migration_dsn_field_user), user, editable, MigrationTags.DSN_USER) { user = it }
        DsnField(stringResource(Res.string.migration_dsn_field_sslmode), sslMode, editable, MigrationTags.DSN_SSLMODE) { sslMode = it }

        // SSL downgrade → WARN (severityColor + a SEPARATE ▲ node, WCAG 1.4.1). No block — the operator may.
        if (sslMode.lowercase().let { it != "require" && !it.startsWith("verify") }) {
            Row(
                modifier = Modifier.fillMaxWidth().testTag(MigrationTags.DSN_SSL_WARNING),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text("▲", color = severityColor(Severity.WARN), style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(Res.string.migration_dsn_ssl_warning),
                    color = severityColor(Severity.WARN),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Password — write-only. Stored state shows the SERVER-masked ***last4 (client never shortens).
        val passwordA11y = stringResource(Res.string.a11y_migration_dsn_password)
        val storedMasked = stored?.passwordMaskedLast4
        Text(
            text = if (storedMasked != null) stringResource(Res.string.migration_dsn_password_set, storedMasked)
            else stringResource(Res.string.migration_dsn_password_unset),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().testTag(MigrationTags.DSN_PASSWORD_MASKED),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                enabled = editable,
                singleLine = true,
                label = { Text(stringResource(Res.string.migration_dsn_field_password)) },
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.weight(1f).testTag(MigrationTags.DSN_PASSWORD).semantics { contentDescription = passwordA11y },
            )
            val revealDesc = stringResource(
                if (reveal) Res.string.a11y_migration_dsn_password_hide else Res.string.a11y_migration_dsn_password_reveal,
            )
            TextButton(
                onClick = { reveal = !reveal },
                enabled = editable,
                modifier = Modifier.testTag(MigrationTags.DSN_PASSWORD_REVEAL).semantics { contentDescription = revealDesc },
            ) { Text(revealDesc, style = MaterialTheme.typography.labelMedium) }
        }

        // tierOrigin — shown in clear (credential-free): BYO changes who runs/backs-up the DB.
        stored?.origin?.let { origin ->
            Text(
                text = stringResource(
                    if (origin == DsnOrigin.BYO) Res.string.migration_dsn_origin_byo else Res.string.migration_dsn_origin_managed,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().testTag(MigrationTags.DSN_ORIGIN),
            )
        }
    }
}

@Composable
private fun DsnField(label: String, value: String, editable: Boolean, tag: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = editable,
        singleLine = true,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}
