package com.tneff.cyppieagents.connector

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.ProviderInfo
import com.tneff.cyppieagents.ui.HintTone
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_connector_fidelity_badge
import kmpcyppieagents.app.shared.generated.resources.a11y_provider
import kmpcyppieagents.app.shared.generated.resources.connector_active
import kmpcyppieagents.app.shared.generated.resources.connector_cap_available
import kmpcyppieagents.app.shared.generated.resources.connector_cap_limited
import kmpcyppieagents.app.shared.generated.resources.connector_cap_unavailable
import kmpcyppieagents.app.shared.generated.resources.connector_capabilities_title
import kmpcyppieagents.app.shared.generated.resources.connector_degraded_note
import kmpcyppieagents.app.shared.generated.resources.connector_dim_coordination
import kmpcyppieagents.app.shared.generated.resources.connector_dim_rate_limit_signal
import kmpcyppieagents.app.shared.generated.resources.connector_dim_reliable_result
import kmpcyppieagents.app.shared.generated.resources.connector_dim_structured_usage
import kmpcyppieagents.app.shared.generated.resources.connector_dim_tool_granularity
import kmpcyppieagents.app.shared.generated.resources.connector_dim_unknown
import kmpcyppieagents.app.shared.generated.resources.connector_feeds_coordination
import kmpcyppieagents.app.shared.generated.resources.connector_feeds_rate_limit_signal
import kmpcyppieagents.app.shared.generated.resources.connector_feeds_reliable_result
import kmpcyppieagents.app.shared.generated.resources.connector_feeds_structured_usage
import kmpcyppieagents.app.shared.generated.resources.connector_feeds_tool_granularity
import kmpcyppieagents.app.shared.generated.resources.connector_fidelity_badge
import kmpcyppieagents.app.shared.generated.resources.connector_fidelity_unknown
import kmpcyppieagents.app.shared.generated.resources.connector_kind_mcp
import kmpcyppieagents.app.shared.generated.resources.connector_kind_stream_json
import kmpcyppieagents.app.shared.generated.resources.connector_provider
import kmpcyppieagents.app.shared.generated.resources.connector_provider_unknown
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-123 (Doc 10, UX-Spec CYP-119) — the connector **capability display** layer: a compact fidelity badge
 * at the agent-window header and a detailed capability panel on demand. Pure read-only honesty (spec §1/§5):
 *
 *  - **Fail-closed:** `null` caps ⇒ "not yet reported", the badge present, every dimension UNAVAILABLE — never
 *    silently rendered as full/available.
 *  - **UNAVAILABLE is visible, not error-red:** degradation is a *declared* connector boundary, not an app
 *    error → the GATED (neutral) tone, never ERROR. Real red stays for real failures.
 *  - **Colour is never the sole carrier (WCAG 1.4.1):** every status carries a distinct glyph (`✓`/`!`/`○`)
 *    AND a text label; the meaning lives in the text.
 *  - **Fidelity ≠ severity ≠ right ≠ identity:** the badge is the Fidelity axis only; the connector identity
 *    (A/B) lives in the panel, not the compact badge.
 */

/**
 * The tri-state → tone mapping (spec §2.1). Reuses the existing [HintTone] vocabulary so the connector chips
 * read in the same Attention/neutral language as the rest of the app — and crucially keeps UNAVAILABLE OUT of
 * the ERROR tone (a declared boundary, not a failure).
 */
fun capabilityTone(status: CapabilityStatus): HintTone = when (status) {
    CapabilityStatus.AVAILABLE -> HintTone.INFO // neutral secondary — an honest fact, NOT a saturated success green
    CapabilityStatus.LIMITED -> HintTone.EFFECT_DEFERRED // amber Attention — "degraded/marked", not an error
    CapabilityStatus.UNAVAILABLE -> HintTone.GATED // neutral onSurfaceVariant — "off", visible, NOT error-red
}

/**
 * The non-colour status glyph (WCAG 1.4.1): distinct per status, plain text (no emoji — CYP-54), reliable on
 * Desktop-JVM. Always rendered together with the status label so neither colour nor glyph is the sole carrier.
 */
fun capabilityGlyph(status: CapabilityStatus): String = when (status) {
    CapabilityStatus.AVAILABLE -> "✓"
    CapabilityStatus.LIMITED -> "!"
    CapabilityStatus.UNAVAILABLE -> "○"
}

/** The content colour for a tri-state status, derived from its [HintTone] so the chip matches the tone palette. */
@Composable
private fun capabilityContentColor(status: CapabilityStatus): Color = when (capabilityTone(status)) {
    HintTone.EFFECT_DEFERRED -> MaterialTheme.colorScheme.onSecondaryContainer // CYP-300 (a0): parity w/ TonedHint
    HintTone.GATED -> MaterialTheme.colorScheme.onSurfaceVariant
    HintTone.INFO -> MaterialTheme.colorScheme.secondary
    HintTone.ERROR -> MaterialTheme.colorScheme.error
}

@Composable
private fun capabilityStatusLabel(status: CapabilityStatus): String = when (status) {
    CapabilityStatus.AVAILABLE -> stringResource(Res.string.connector_cap_available)
    CapabilityStatus.LIMITED -> stringResource(Res.string.connector_cap_limited)
    CapabilityStatus.UNAVAILABLE -> stringResource(Res.string.connector_cap_unavailable)
}

@Composable
private fun connectorKindLabel(kind: ConnectorKind): String = when (kind) {
    ConnectorKind.STREAM_JSON -> stringResource(Res.string.connector_kind_stream_json)
    ConnectorKind.MCP -> stringResource(Res.string.connector_kind_mcp)
}

@Composable
private fun capabilityDimensionLabel(dim: CapabilityDimension): String = when (dim) {
    CapabilityDimension.STRUCTURED_USAGE -> stringResource(Res.string.connector_dim_structured_usage)
    CapabilityDimension.TOOL_GRANULARITY -> stringResource(Res.string.connector_dim_tool_granularity)
    CapabilityDimension.RELIABLE_RESULT -> stringResource(Res.string.connector_dim_reliable_result)
    CapabilityDimension.RATE_LIMIT_SIGNAL -> stringResource(Res.string.connector_dim_rate_limit_signal)
    CapabilityDimension.COORDINATION -> stringResource(Res.string.connector_dim_coordination)
    CapabilityDimension.UNKNOWN -> stringResource(Res.string.connector_dim_unknown)
}

/** The muted "feeds …" secondary line per dimension (which Mediator function degrades). `null` for [UNKNOWN]. */
@Composable
private fun capabilityFeedsLabel(dim: CapabilityDimension): String? = when (dim) {
    CapabilityDimension.STRUCTURED_USAGE -> stringResource(Res.string.connector_feeds_structured_usage)
    CapabilityDimension.TOOL_GRANULARITY -> stringResource(Res.string.connector_feeds_tool_granularity)
    CapabilityDimension.RELIABLE_RESULT -> stringResource(Res.string.connector_feeds_reliable_result)
    CapabilityDimension.RATE_LIMIT_SIGNAL -> stringResource(Res.string.connector_feeds_rate_limit_signal)
    CapabilityDimension.COORDINATION -> stringResource(Res.string.connector_feeds_coordination)
    CapabilityDimension.UNKNOWN -> null
}

/**
 * The reusable tri-state status chip (spec §2.1) — glyph + label in one merged node, tagged
 * [ConnectorTags.capabilityStatus]. The same chip serves the live panel (scope = agentId) and the B opt-in
 * preview (scope = [CAPABILITY_PREVIEW_SCOPE]) later. `mergeDescendants` keeps glyph+label as one a11y unit
 * (colour never alone — the label text carries the meaning).
 */
@Composable
fun CapabilityStatusChip(status: CapabilityStatus, scope: String, dim: CapabilityDimension) {
    val color = capabilityContentColor(status)
    Row(
        modifier = Modifier
            .testTag(ConnectorTags.capabilityStatus(scope, dim))
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(capabilityGlyph(status), color = color, style = MaterialTheme.typography.bodySmall)
        Text(capabilityStatusLabel(status), color = color, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Compact provider qualifier-chip at the agent-window header (CYP-137, `provider-display-spec.md` §2.1/§2.2).
 * Provider is the **fourth, separate axis** — provider ≠ identity ≠ connector-kind ≠ fidelity, never merged: a
 * neutral, **subordinate** text qualifier with **no** identity hue, **no** severity colour, **no** logo/marketing
 * (anti-hype, spec §1/§5). **Fail-closed by absence** (like CYP-55): rendered **only** when the provider is
 * known; `null` ⇒ NOTHING is drawn (never an invented "Claude") — the agent stays fully named by its identity.
 *
 * The visible text is the provider's [ProviderInfo.displayName] **alone** (the short label, e.g. "Claude"); the
 * labelled `contentDescription` (`a11y_provider` = "Anbieter: %1$s") carries the meaning so colour is never the
 * sole carrier (WCAG 1.4.1) and the chip stays unobtrusive. [ProviderInfo.id] is the stable key, never shown.
 */
@Composable
fun ConnectorProviderChip(provider: ProviderInfo?, agentId: String, modifier: Modifier = Modifier) {
    // Fail-closed by absence: unknown provider ⇒ no chip (no phantom). Identity is complete without it.
    val p = provider ?: return
    val description = stringResource(Res.string.a11y_provider, p.displayName)
    Text(
        text = p.displayName,
        style = MaterialTheme.typography.labelSmall,
        // Neutral + subordinate — NOT an identity hue (else it reads as identity) and NOT a severity colour
        // (provider is not a state). onSurfaceVariant matches the muted-qualifier tone.
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .testTag(ConnectorTags.provider(agentId))
            .semantics { contentDescription = description },
    )
}

/**
 * Compact fidelity badge at the agent-window header (spec §2.2). **Fail-closed by absence** (like CYP-55):
 * present **only** when caps are degraded or `null`; a full-fidelity agent renders NOTHING (no false
 * "all-green" seal). Clickable (opens the detail panel via [onClick]); carries the a11y label and the Fidelity
 * axis only — the connector identity lives in the panel, not here.
 */
@Composable
fun ConnectorCapabilityBadge(
    caps: Capabilities?,
    agentId: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    /** CYP-280: caps read in flight → suppress the badge entirely. `null` caps during a load ("not loaded yet")
     *  must NOT read as `○ not-reported`; the honest `○` is only for a SETTLED-null (genuinely unreported). */
    loading: Boolean = false,
) {
    // CYP-280: while the caps are still loading (e.g. a fresh project switch), show no fidelity claim at all —
    // a transient `○` on a full-fidelity agent is actively wrong (worse than absence).
    if (loading) return
    // Full fidelity → no badge (fail-closed by absence; absence == "all available").
    if (caps != null && !caps.isDegraded) return

    val a11y = stringResource(Res.string.a11y_connector_fidelity_badge)
    // null caps ⇒ "not yet reported" (neutral GATED, `○`); degraded ⇒ "Limited" (amber Attention, `!`).
    val unknown = caps == null
    val label = if (unknown) {
        stringResource(Res.string.connector_fidelity_unknown)
    } else {
        stringResource(Res.string.connector_fidelity_badge)
    }
    val glyph = if (unknown) "○" else "!"
    val color = if (unknown) {
        MaterialTheme.colorScheme.onSurfaceVariant // GATED — fail-closed, not yet reported (NOT error-red)
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer // CYP-300 (a0): EFFECT_DEFERRED — secondary (blue) Attention, not tertiary
    }
    Row(
        modifier = modifier
            .testTag(ConnectorTags.fidelityBadge(agentId))
            .clickable(onClick = onClick)
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { contentDescription = a11y },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(glyph, color = color, style = MaterialTheme.typography.bodySmall)
        Text(label, color = color, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Detailed per-agent capability panel (spec §2.3): title, active-connector identity line, the five tri-state
 * dimension rows (label + status chip + muted "feeds" line), and the honesty footnote. **Fail-closed:** when
 * caps are `null` the active line reads "not yet reported" and every known dimension renders UNAVAILABLE — the
 * rows are NEVER omitted (absence of a status is shown, never assumed available).
 */
@Composable
fun CapabilityPanel(
    caps: Capabilities?,
    agentId: String,
    modifier: Modifier = Modifier,
    /** CYP-137 provider — the top identity line; `null` ⇒ "not yet reported" (fail-closed, never invented). */
    provider: ProviderInfo? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().testTag(ConnectorTags.capabilityPanel(agentId)),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(Res.string.connector_capabilities_title),
            style = MaterialTheme.typography.titleSmall,
        )

        // Provider — the TOP identity line (CYP-137, spec §2.3): reads the axes honestly outside-in, *womit*
        // (provider) → *wie* (connector-kind, below) → *was möglich ist* (the five fidelity rows). Identity
        // text (≠ fidelity). Fail-closed: null ⇒ "not yet reported", never omitted-as-if-full, never invented.
        val providerText = if (provider != null) {
            stringResource(Res.string.connector_provider, provider.displayName)
        } else {
            stringResource(Res.string.connector_provider_unknown)
        }
        Text(
            text = providerText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(ConnectorTags.provider(agentId)),
        )

        // Active connector — identity axis (≠ fidelity). When unreported we say so honestly, never a faked kind.
        val activeText = if (caps != null) {
            stringResource(Res.string.connector_active, connectorKindLabel(caps.kind))
        } else {
            stringResource(Res.string.connector_fidelity_unknown)
        }
        Text(
            text = activeText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(ConnectorTags.activeConnector(agentId)),
        )

        // Five dimension rows. Real rows from caps; null ⇒ all five known dimensions UNAVAILABLE (fail-closed,
        // never omitted), so "not yet reported" shows the full honest table rather than a blank.
        val rows = caps?.rows ?: failClosedRows()
        rows.forEach { row -> DimensionRow(agentId, row) }

        // Honesty footnote — limited/unavailable are marked, never faked (spec §2.3/§5).
        Text(
            text = stringResource(Res.string.connector_degraded_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The fail-closed dimension set: every known dimension reported UNAVAILABLE (spec §5.1, no phantom status). */
private fun failClosedRows(): List<CapabilityRow> =
    CapabilityDimension.entries
        .filter { it != CapabilityDimension.UNKNOWN }
        .map { CapabilityRow(it, CapabilityStatus.UNAVAILABLE) }

@Composable
private fun DimensionRow(agentId: String, row: CapabilityRow) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(ConnectorTags.capability(agentId, row.dimension)),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = capabilityDimensionLabel(row.dimension),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(0.62f),
            )
            CapabilityStatusChip(row.status, agentId, row.dimension)
        }
        // Muted "feeds …" secondary line — which Mediator function this dimension degrades (spec §2.3).
        capabilityFeedsLabel(row.dimension)?.let { feeds ->
            Text(
                text = feeds,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
