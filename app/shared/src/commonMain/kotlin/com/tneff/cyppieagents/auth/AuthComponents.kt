package com.tneff.cyppieagents.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import com.tneff.cyppieagents.ui.hintGlyph
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_auth_email
import kmpcyppieagents.app.shared.generated.resources.a11y_auth_password_hide
import kmpcyppieagents.app.shared.generated.resources.a11y_auth_password_show
import kmpcyppieagents.app.shared.generated.resources.auth_email_label
import kmpcyppieagents.app.shared.generated.resources.auth_rate_limited
import kmpcyppieagents.app.shared.generated.resources.auth_rate_limited_wait
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The only new layout token for the auth surface (auth-tokens.json): the centred auth form card's
 * max width. Phone → full width with padding; Desktop → centred and capped (not edge-to-edge). A plain
 * max-width cap in the adaptive spirit of CYP-156/159, measured at the card, not window-dependent.
 */
val AUTH_FORM_MAX_WIDTH: Dp = 400.dp

/**
 * The shared frame for every auth screen: a vertically-scrollable, centred [Column] capped at
 * [AUTH_FORM_MAX_WIDTH]. [tag] identifies the form container (`auth.<scope>.form`).
 */
@Composable
fun AuthFormCard(tag: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = AUTH_FORM_MAX_WIDTH)
                .fillMaxWidth()
                .padding(24.dp)
                .testTag(tag),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/** Screen title as an a11y heading (reuse of the `SettingsPanel.SectionHeading` pattern). */
@Composable
fun AuthTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
    )
}

/**
 * The shared e-mail input (label `auth_email_label`, a11y `a11y_auth_email`, `KeyboardType.Email`).
 * [isError] couples the visual + semantic error marking (the toned error line carries the message).
 */
@Composable
fun AuthEmailField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    isError: Boolean,
    tag: String,
    imeAction: ImeAction = ImeAction.Next,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val label = stringResource(Res.string.auth_email_label)
    val a11y = stringResource(Res.string.a11y_auth_email)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        isError = isError,
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = imeAction),
        keyboardActions = keyboardActions,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .semantics { contentDescription = a11y },
    )
}

/**
 * The shared masked password input. When [revealTag] is non-null it carries a **text-label** reveal
 * toggle (CYP-99 reuse: no emoji, CYP-54); when null the field is plain masked (the Register/Reset
 * *confirm* fields — the tag contract gives one reveal per scope, on the primary field, so the confirm
 * field has no toggle). The reveal state is local; the clear text shows only in the field, never echoed
 * separately into the a11y tree. [label]/[contentDesc] vary per use (password / confirm / new).
 */
@Composable
fun AuthPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    isError: Boolean,
    label: StringResource,
    contentDesc: StringResource,
    fieldTag: String,
    revealTag: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    var reveal by remember { mutableStateOf(false) }
    val labelText = stringResource(label)
    val a11y = stringResource(contentDesc)
    val field: @Composable (Modifier) -> Unit = { fieldModifier ->
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            isError = isError,
            singleLine = true,
            label = { Text(labelText) },
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
            keyboardActions = keyboardActions,
            modifier = fieldModifier
                .testTag(fieldTag)
                .semantics { contentDescription = a11y },
        )
    }
    if (revealTag == null) {
        field(Modifier.fillMaxWidth())
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        field(Modifier.weight(1f))
        val revealDesc = stringResource(
            if (reveal) Res.string.a11y_auth_password_hide else Res.string.a11y_auth_password_show,
        )
        TextButton(
            onClick = { reveal = !reveal },
            enabled = enabled,
            modifier = Modifier
                .testTag(revealTag)
                .semantics { contentDescription = revealDesc },
        ) {
            // CYP-99/CYP-54: a reliable text label, not an emoji; doubles as the a11y description.
            Text(revealDesc, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * A [TonedHint] that also **announces** its state change to screen readers via `liveRegion`
 * (auth-spec §8.2 — the one new additive a11y pattern). [mode] = Assertive for errors/throttling,
 * Polite for neutral confirmations. The tag/liveRegion sit on the same node (QA/CYP-7 knows it).
 */
@Composable
fun AnnouncingHint(text: String, tone: HintTone, tag: String, mode: LiveRegionMode) {
    TonedHint(text, tone, tag, modifier = Modifier.semantics { liveRegion = mode })
}

/**
 * A plain, **untagged** INFO note (glyph + secondary text, the [HintTone.INFO] look) for the few
 * informational lines the frozen tag contract gives no node — the password-rule hint and the verify
 * success body. Untagged on purpose: it invents no `auth.*` tag outside `auth-tags.md`.
 */
@Composable
fun AuthInfoNote(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = hintGlyph(HintTone.INFO),
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = text,
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Honest 429 text: with a server `retryAfter` → the "…in %1$s…" variant, else the plain one. */
@Composable
fun rateLimitedText(retryAfter: String?): String =
    if (retryAfter != null) {
        stringResource(Res.string.auth_rate_limited_wait, retryAfter)
    } else {
        stringResource(Res.string.auth_rate_limited)
    }
