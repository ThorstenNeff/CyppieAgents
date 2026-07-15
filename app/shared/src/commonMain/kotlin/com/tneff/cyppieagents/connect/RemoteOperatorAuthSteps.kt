package com.tneff.cyppieagents.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.auth.AuthPasswordField
import com.tneff.cyppieagents.eventlog.severityColor
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.net.hub.operator.ui.EnrollTone
import com.tneff.cyppieagents.net.hub.operator.ui.OperatorAuthTags
import com.tneff.cyppieagents.net.hub.operator.ui.enrollStrengthUi
import com.tneff.cyppieagents.net.hub.operator.vault.CredentialPolicy
import com.tneff.cyppieagents.net.hub.operator.vault.EnrollOutcome
import com.tneff.cyppieagents.net.hub.operator.vault.PassphraseStrength
import com.tneff.cyppieagents.net.hub.operator.vault.StrengthVerdict
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_remote_pop_enroll_clipboard_notice
import kmpcyppieagents.app.shared.generated.resources.a11y_remote_pop_uv_coverage
import kmpcyppieagents.app.shared.generated.resources.remote_pop_enroll_blocklisted
import kmpcyppieagents.app.shared.generated.resources.remote_pop_enroll_clipboard_notice
import kmpcyppieagents.app.shared.generated.resources.remote_pop_enroll_copy
import kmpcyppieagents.app.shared.generated.resources.remote_pop_enroll_mismatch
import kmpcyppieagents.app.shared.generated.resources.remote_pop_enroll_passphrase
import kmpcyppieagents.app.shared.generated.resources.remote_pop_enroll_passphrase_confirm
import kmpcyppieagents.app.shared.generated.resources.remote_pop_enroll_strength_hint
import kmpcyppieagents.app.shared.generated.resources.remote_pop_enroll_suggest
import kmpcyppieagents.app.shared.generated.resources.remote_pop_enroll_suggest_use
import kmpcyppieagents.app.shared.generated.resources.remote_pop_enroll_suggested
import kmpcyppieagents.app.shared.generated.resources.remote_pop_enroll_suggested_save
import kmpcyppieagents.app.shared.generated.resources.remote_pop_enroll_too_weak
import kmpcyppieagents.app.shared.generated.resources.remote_pop_enroll_type_own
import kmpcyppieagents.app.shared.generated.resources.remote_pop_passphrase_body
import kmpcyppieagents.app.shared.generated.resources.remote_pop_passphrase_title
import kmpcyppieagents.app.shared.generated.resources.remote_pop_strength_strong
import kmpcyppieagents.app.shared.generated.resources.remote_pop_uv_coverage
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-542 / B1 (a-render) — the operator-UV step renderers for [HubConnectUiState.PassphrasePrompt] (auth-time) and
 * [HubConnectUiState.SetPassphrase] (AC-1 enroll), wired to [HubConnectViewModel] (PO ruling Q2=A: a new VM-wired
 * composable, not extending the String/PIN `OperatorAuthDialog`). Reuses `enrollStrengthUi` (the R4 fill marker),
 * `severityColor` (WARN-amber), and the hardened `RecoveryCodesReveal` reveal pattern (A0) for the diceware display.
 * Render values are pulled 1:1 from the render-oracle `b03d4b7c` (colours/glyphs/fills) + the strings manifest.
 *
 * **Q1 RULING (PO + Assist): rec A + hardening riders** (rec B rejected as *illusory* literal-compliance — Compose
 * `BasicTextField`/IME/edit-buffer allocate immutable Strings for the field text regardless of the backing store, so B
 * moves the String-linger under an abstraction while piling hand-rolled masking/reveal/IME/selection onto the
 * crown-jewel path). A and B carry the SAME real at-rest exposure (both bounded by framework linger you cannot null); A
 * has strictly less surface + matches the PIN idiom. Riders below are binding (Assist gates them at the B1 full gate).
 */

// ── Q1 swap point ────────────────────────────────────────────────────────────────────────────────────────────────
/**
 * The single passphrase field seam. rec A: the masked codebase [AuthPasswordField] (String), reveal via text-label.
 * Binding hardening riders (PO/Assist — gated at the B1 full gate):
 *  1. Convert to `CharArray` ONLY at the VM boundary (`toCharArray()`); crypto/VM/UV stays CharArray e2e. **Ownership
 *     note:** the converted array is owned + zeroized by the VM/UV (the coordinator→UV reads it asynchronously and
 *     H-1-zeroizes in `PassphraseUserVerification.verify`'s finally; enroll's failure path zeroizes it) — the seam does
 *     NOT null it after submit, which would race (zero it before the async UV reads it). Same real guarantee, correct order.
 *  2. **No derived String copies of the plaintext** — no `trim`/`substring`/`"$v"`/concat/format/split (each mints a
 *     new immutable String). Validation runs on the `CharArray` (strength) or on non-secret metadata (length/equality).
 *  3. Field state cleared on **submit AND dispose** (`value=""`); never hoisted into a `remember`/VM state that outlives
 *     the screen (the `mutableStateOf` here dies with the composable).
 *  4. No capture: no logging/crash-report/analytics; no `derivedStateOf`/Snapshot of the plaintext; masked password
 *     field (no autofill/IME learning); no clipboard-copy on the input.
 *  5. No leak sinks: no a11y node reads the raw value (`contentDescription` = the static label, never `value`); no
 *     clipboard on the input (the diceware reveal is the separate, intended egress). **Android `FLAG_SECURE` on the
 *     enroll screen is a deferred Android-only follow-up** (needs an androidMain actual; Desktop — the dogfood target —
 *     n/a); flagged, not blocking the Desktop path.
 *
 * **Residual (rider 6):** the passphrase String lingers for the input duration + until GC — a platform-inherent CMP
 * residual that **option B would NOT remove** (Compose IME/edit-buffer allocate Strings regardless of backing);
 * accepted + bounded for the dogfood window, same rationale as the CYP-460 PIN String-backing. Revisit when an
 * OS-native secure-input path lands or the threat model extends past the local operator machine. H-1 stays enforced
 * where the CODE owns the representation (vault/KDF/key-hold — CharArray in, KEK zeroized).
 */
@Composable
internal fun PassphraseInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: org.jetbrains.compose.resources.StringResource,
    contentDesc: org.jetbrains.compose.resources.StringResource,
    fieldTag: String,
    enabled: Boolean = true,
    isError: Boolean = false,
    onSubmit: () -> Unit = {},
) {
    AuthPasswordField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        isError = isError,
        label = label,
        contentDesc = contentDesc,
        fieldTag = fieldTag,
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
    )
}
// ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────

/** Auth-time: the real UV asks for the App-Passphrase to unlock the sealed device-key vault (no-hardware unlock). */
@Composable
internal fun PassphrasePromptStep(viewModel: HubConnectViewModel) {
    var pass by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { pass = "" } } // never let the field state linger past this step
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp).testTag(OperatorAuthTags.PASSPHRASE_PROMPT),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(Res.string.remote_pop_passphrase_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(Res.string.remote_pop_passphrase_body), style = MaterialTheme.typography.bodyMedium)
        val submit = {
            if (pass.isNotEmpty()) { viewModel.submitPassphrase(pass.toCharArray()); pass = "" }
        }
        PassphraseInput(
            value = pass, onValueChange = { pass = it },
            label = Res.string.remote_pop_passphrase_title, contentDesc = Res.string.remote_pop_passphrase_body,
            fieldTag = OperatorAuthTags.PIN_FIELD, onSubmit = submit,
        )
        UvCoverageLine()
        Button(onClick = submit, enabled = pass.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.remote_pop_passphrase_title))
        }
        OutlinedButton(onClick = viewModel::cancelPassphrase, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.remote_pop_enroll_type_own)) // reuse a neutral cancel affordance label
        }
    }
}

/** AC-1 enroll: set the App-Passphrase (diceware one-click default OR type-your-own), then auto-reconnect (AC-2). */
@Composable
internal fun SetPassphraseStep(state: HubConnectUiState.SetPassphrase, viewModel: HubConnectViewModel) {
    val enrolling = state.phase == EnrollPhase.ENROLLING
    var suggestion by remember { mutableStateOf<CharArray?>(null) }
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    DisposableEffect(Unit) {
        onDispose { suggestion?.fill(' '); suggestion = null; pass = ""; confirm = "" } // zeroize the held secret on leave
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp).testTag(OperatorAuthTags.ENROLL_PIN_SET),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(Res.string.remote_pop_enroll_passphrase), style = MaterialTheme.typography.titleMedium)

        // §1a — the diceware one-click default (A0: reuse the hardened RecoveryCodesReveal reveal/copy path).
        Column(
            modifier = Modifier.fillMaxWidth().testTag(OperatorAuthTags.ENROLL_SUGGESTED),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(Res.string.remote_pop_enroll_suggested), style = MaterialTheme.typography.bodyMedium)
            val current = suggestion
            if (current != null) {
                DicewareReveal(current)
                Text(
                    stringResource(Res.string.remote_pop_enroll_suggested_save),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, // neutral fact, no alarm
                )
                Button(
                    onClick = { if (!enrolling) viewModel.setEnrollPassphrase(current.copyOf()) },
                    enabled = !enrolling,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(Res.string.remote_pop_enroll_suggest_use)) } // filled primary
            }
            OutlinedButton(
                onClick = { suggestion?.fill(' '); suggestion = viewModel.suggestPassphrase() },
                enabled = !enrolling,
                modifier = Modifier.fillMaxWidth().testTag(OperatorAuthTags.ENROLL_SUGGEST),
            ) { Text(stringResource(Res.string.remote_pop_enroll_suggest)) }
        }

        // §1b — type-your-own field-pair + §2 live strength meter.
        Text(
            stringResource(Res.string.remote_pop_enroll_type_own),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(OperatorAuthTags.ENROLL_TYPE_OWN),
        )
        PassphraseInput(
            value = pass, onValueChange = { pass = it }, enabled = !enrolling,
            label = Res.string.remote_pop_enroll_passphrase, contentDesc = Res.string.remote_pop_enroll_passphrase,
            fieldTag = OperatorAuthTags.ENROLL_PASSPHRASE_FIELD,
        )
        PassphraseInput(
            value = confirm, onValueChange = { confirm = it }, enabled = !enrolling,
            label = Res.string.remote_pop_enroll_passphrase_confirm, contentDesc = Res.string.remote_pop_enroll_passphrase_confirm,
            fieldTag = OperatorAuthTags.ENROLL_PASSPHRASE_CONFIRM,
        )
        if (pass.isNotEmpty()) StrengthMeter(pass)

        // Error surfacing — the typed verdict causes (from the core enroll outcome, F-#4) with the render-oracle tones.
        EnrollOutcomeLine(state.outcome)
        // Local mismatch gate (§1b): amber-neutral hint (the core never sees a mismatched submit).
        val matched = pass.isNotEmpty() && pass == confirm

        if (enrolling) {
            // F1: announce the enrolling progress (otherwise the bare spinner is SR-silent). Polite — advisory progress.
            val enrollingDesc = stringResource(Res.string.remote_pop_enroll_passphrase)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = enrollingDesc },
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            Button(
                onClick = { viewModel.setEnrollPassphrase(pass.toCharArray()); pass = ""; confirm = "" },
                enabled = matched,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(Res.string.remote_pop_enroll_passphrase)) }
            if (pass.isNotEmpty() && confirm.isNotEmpty() && !matched) {
                Text(
                    stringResource(Res.string.remote_pop_enroll_mismatch),
                    color = MaterialTheme.colorScheme.error, // error tone (retryable), NOT errorContainer
                    style = MaterialTheme.typography.bodySmall,
                    // F1: a mismatch is an ASSERTIVE announcement (the SR interrupts to read the already-distinct string).
                    modifier = Modifier.testTag(OperatorAuthTags.error("mismatch"))
                        .semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
        }
        // CYP-584 F1a (§2.1, T1): the Cancel/leave affordance stays visible in BOTH branches — during ENROLLING only
        // the submit is replaced by the spinner, but the operator must ALWAYS have an escape (no dead-wait spinner from
        // which there is no exit). Reuses cancelEnroll, which now actually cancels the in-flight seal (the F1a VM fix).
        OutlinedButton(
            onClick = viewModel::cancelEnroll,
            modifier = Modifier.fillMaxWidth().testTag(OperatorAuthTags.ENROLL_CANCEL),
        ) { Text(stringResource(Res.string.remote_pop_enroll_type_own)) }
    }
}

/**
 * A0 (F2 fix) — the generated diceware passphrase, shown readable (NOT masked), via the **same hardened reveal path as
 * `RecoveryCodesReveal`**: a [SelectionContainer] (manual-copy fallback, esp. Web) + a real copy-to-clipboard button
 * (reusing that path's `remote_recovery_codes_copy` action) + the clipboard-egress disclosure. The button makes the
 * disclosure HONEST (a copy really happens). This is the intended, DISCLOSED recovery egress — the generated passphrase
 * IS the recovery secret the user must save, framed like backup codes — and is **distinct from the clipboard-free
 * `PassphraseInput`** (rider 5: no clipboard on the masked input). The secret is a CharArray held by the caller.
 */
@Composable
private fun DicewareReveal(passphrase: CharArray) {
    val clipboard = LocalClipboardManager.current
    val text = passphrase.concatToString() // inherent to a readable reveal (the user must READ + save it)
    val a11y = stringResource(Res.string.a11y_remote_pop_enroll_clipboard_notice)
    var copied by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SelectionContainer {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
            )
        }
        TextButton( // the real copy — the RecoveryCodesReveal-path reuse (A0). Label = the dedicated UIUX2 key (the
            // TextButton text IS the accessible name — no separate a11y contentDescription needed).
            onClick = { clipboard.setText(AnnotatedString(text)); copied = true },
            modifier = Modifier.testTag(OperatorAuthTags.ENROLL_CLIPBOARD_NOTICE + ".copy"),
        ) { Text(stringResource(Res.string.remote_pop_enroll_copy)) }
        // F2-honesty-residual: the notice is PAST-TENSE ("Copied to clipboard…") — show it ONLY after a real copy, so
        // it is never a premature completion-claim before any copy happened. (Manual SelectionContainer copy is a
        // fallback; the explicit button is the disclosed egress the notice attaches to.)
        if (copied) {
            Text(
                stringResource(Res.string.remote_pop_enroll_clipboard_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, // neutral, NO glyph (informational egress disclosure)
                modifier = Modifier.fillMaxWidth().testTag(OperatorAuthTags.ENROLL_CLIPBOARD_NOTICE)
                    .semantics { contentDescription = a11y },
            )
        }
    }
}

/** §2 — the live strength meter (advisory; the CORE `verdict()` is the fail-closed gate, F-#4). Fill role = `primary`,
 *  EXCEPT BLOCKLISTED which uses the `outline`-damped fill (R4 [enrollStrengthUi] `fillDamped`) so it can't lie "strong". */
@Composable
private fun StrengthMeter(passphrase: String) {
    val verdict = PassphraseStrength.verdict(passphrase.toCharArray(), CredentialPolicy.SOFTWARE_MIN_ENTROPY_BITS)
    val ui = enrollStrengthUi(verdict)
    val fillColor = if (ui.fillDamped) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
    Column(
        // F1 (WCAG 4.1.3): the live strength readout is a POLITE live region so a screenreader announces the verdict
        // (strong / too-weak / blocklisted) + hint as the operator types — the distinct cause-strings are no longer SR-mute.
        modifier = Modifier.fillMaxWidth().testTag(OperatorAuthTags.ENROLL_STRENGTH)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Track + proportional fill (custom so the fill COLOUR role is exact, not LinearProgressIndicator's primary default).
        Box(
            Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(Modifier.fillMaxWidth(ui.meterFraction).height(6.dp).clip(RoundedCornerShape(3.dp)).background(fillColor))
        }
        // Verdict line: OK ● primary · TOO_WEAK ▲ WARN-amber · BLOCKLISTED (no glyph) error-tone.
        when (verdict) {
            StrengthVerdict.OK -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("● ", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(Res.string.remote_pop_strength_strong), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StrengthVerdict.TOO_WEAK -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("▲ ", color = severityColor(Severity.WARN), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(Res.string.remote_pop_enroll_too_weak), color = severityColor(Severity.WARN),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(OperatorAuthTags.error("tooWeak")))
            }
            StrengthVerdict.BLOCKLISTED -> Text( // no glyph (error tone + self-describing copy are the two non-colour carriers)
                stringResource(Res.string.remote_pop_enroll_blocklisted),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(OperatorAuthTags.error("blocklisted")))
        }
        Text(stringResource(Res.string.remote_pop_enroll_strength_hint), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The typed core-enroll refusal (F-#4) with the render-oracle tone: TooWeak ▲ WARN-amber · Blocklisted error-tone.
 *  F1: each refusal is an ASSERTIVE live region (the SR interrupts to read the already-distinct cause-string). */
@Composable
private fun EnrollOutcomeLine(outcome: EnrollOutcome?) {
    when (outcome) {
        EnrollOutcome.TooWeak -> Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        ) {
            Text("▲ ", color = severityColor(Severity.WARN), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(Res.string.remote_pop_enroll_too_weak), color = severityColor(Severity.WARN),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(OperatorAuthTags.error("tooWeak")))
        }
        EnrollOutcome.Blocklisted -> Text(
            stringResource(Res.string.remote_pop_enroll_blocklisted),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(OperatorAuthTags.error("blocklisted"))
                .semantics { liveRegion = LiveRegionMode.Assertive })
        else -> Unit // Enrolled/MigrationFailed/AlreadyEnrolled/null — not a strength refusal
    }
}

/** §4 — the 1-UV-for-N coverage line: neutral (no glyph, no green), the honest "one confirmation covers the hubs you open now". */
@Composable
private fun UvCoverageLine() {
    val a11y = stringResource(Res.string.a11y_remote_pop_uv_coverage)
    Text(
        stringResource(Res.string.remote_pop_uv_coverage),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag(OperatorAuthTags.UV_COVERAGE).semantics { contentDescription = a11y },
    )
}
