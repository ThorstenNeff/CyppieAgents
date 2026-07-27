package com.tneff.cyppieagents.net.hub.trust

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.tneff.cyppieagents.eventlog.severityColorFor
import com.tneff.cyppieagents.model.HubDescriptorValidity
import com.tneff.cyppieagents.model.HubTrustState
import com.tneff.cyppieagents.model.Severity
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_hub_trust_pending
import kmpcyppieagents.app.shared.generated.resources.a11y_hub_trust_rejected
import kmpcyppieagents.app.shared.generated.resources.a11y_hub_trust_stale
import kmpcyppieagents.app.shared.generated.resources.a11y_hub_trust_trusted
import kmpcyppieagents.app.shared.generated.resources.a11y_hub_trust_unknown
import kmpcyppieagents.app.shared.generated.resources.hub_trust_pending
import kmpcyppieagents.app.shared.generated.resources.hub_trust_rejected
import kmpcyppieagents.app.shared.generated.resources.hub_trust_stale
import kmpcyppieagents.app.shared.generated.resources.hub_trust_trusted
import kmpcyppieagents.app.shared.generated.resources.hub_trust_unknown
import org.jetbrains.compose.resources.StringResource

/**
 * CYP-808 — **the ONE central trust-tone source** for the per-hub trust badge (axis a, [HubTrustState]). The Compose
 * twin of web-ts `hubTrustView.ts` (CYP-801): identical CONTRACT — glyph FORMS (◯◔●⊘◑), the label WORD, a11y, and the
 * never-green honesty. Modelled 1:1 on [com.tneff.cyppieagents.eventlog.severityColor] (a pure `*For(state, scheme,
 * dark)` core + a `@Composable` wrapper), so **no trust arm picks a colour inline** — every consumer (badge, and any
 * future list-row / connect-strip) draws its state colour from here.
 *
 * **The trust-tone truth (never-green DS, CYP-803 — the D4 canonical KDoc):**
 *  - **TRUSTED = `onSurface` (full-emphasis NEUTRAL), NEVER green / `primary` / literal-affirm.** Trust is
 *    issuer-vouched **and revocable**, so it earns no positive accent — only the *fuller* emphasis (`onSurface`) that
 *    keeps it DISTINCT from unknown/pending's muted `onSurfaceVariant` (over-neutralising TRUSTED into the absence
 *    tone is the opposite failure — CYP-747 tooth 8). Both directions are pinned by `HubTrustToneNeverGreenTest`.
 *  - **UNKNOWN / PENDING = `onSurfaceVariant`** (muted, calm — no trusted look). PENDING ≠ UNKNOWN (distinct state).
 *  - **REJECTED / STALE = WARN-amber** via the ONE [severityColorFor] `Severity.WARN` source (day+night-safe, CYP-274/300).
 *    Two DELIBERATE Compose divergences from web-ts (contract stays identical — glyph+copy): STALE uses amber, NOT
 *    `tertiary` (which flips green at night, CYP-300 → inverted "ok/green" meaning); REJECTED uses amber, NOT `error`
 *    red, to follow Compose's existing "protective-not-broken" trust-refusal doctrine (`HubConnectSelection` arms).
 *    Distinct from each other via GLYPH (`⊘` terminal-refused vs `◑` freshness-gap) + copy, never colour alone.
 */
data class HubTrustToneSpec(
    /** Distinct FORM per state so colour is never the sole carrier (WCAG 1.4.1): ◯◔●⊘◑. */
    val glyph: String,
    /** The state colour — the SINGLE source of trust colour (never inline `colorScheme.x` at a call site). */
    val color: Color,
    /** The meaning WORD (the a11y carrier), resolved by the badge via `stringResource`. */
    val labelKey: StringResource,
    /** The full a11y description. */
    val a11yKey: StringResource,
    /** The lowercase presentation token — the `hubTrust.<hubId>.<stateToken>` anchor suffix (= `state.name.lowercase()`). */
    val stateToken: String,
)

/**
 * The pure tone core (no `@Composable`, unit-testable): `HubTrustState → HubTrustToneSpec`. The `when` is keyed by the
 * real [HubTrustState] so a new enum value fails to COMPILE here rather than silently rendering nothing.
 */
fun hubTrustToneFor(state: HubTrustState, scheme: ColorScheme, dark: Boolean): HubTrustToneSpec = when (state) {
    HubTrustState.UNKNOWN -> HubTrustToneSpec("◯", scheme.onSurfaceVariant, Res.string.hub_trust_unknown, Res.string.a11y_hub_trust_unknown, "unknown")
    HubTrustState.PENDING -> HubTrustToneSpec("◔", scheme.onSurfaceVariant, Res.string.hub_trust_pending, Res.string.a11y_hub_trust_pending, "pending")
    // TRUSTED: full-emphasis NEUTRAL (onSurface), NEVER primary/green (overclaim) and NEVER onSurfaceVariant (collapse
    // into the absence tone). Both mutations reddened by HubTrustToneNeverGreenTest (CYP-803, CYP-747 teeth 1/7/8).
    HubTrustState.TRUSTED -> HubTrustToneSpec("●", scheme.onSurface, Res.string.hub_trust_trusted, Res.string.a11y_hub_trust_trusted, "trusted")
    HubTrustState.REJECTED -> HubTrustToneSpec("⊘", severityColorFor(Severity.WARN, scheme, dark), Res.string.hub_trust_rejected, Res.string.a11y_hub_trust_rejected, "rejected")
    HubTrustState.STALE -> HubTrustToneSpec("◑", severityColorFor(Severity.WARN, scheme, dark), Res.string.hub_trust_stale, Res.string.a11y_hub_trust_stale, "stale")
}

/** The `@Composable` render-site wrapper — mirrors [com.tneff.cyppieagents.eventlog.severityColor] (dark = a low-luminance surface). */
@Composable
fun hubTrustTone(state: HubTrustState): HubTrustToneSpec =
    hubTrustToneFor(state, MaterialTheme.colorScheme, MaterialTheme.colorScheme.surface.luminance() < 0.5f)

/**
 * The two-signal, **fail-closed** badge-state derivation (mirrors web-ts `hubTrustBadgeState`): a [HubDescriptorValidity.MALFORMED]
 * descriptor collapses the pill to [HubTrustState.UNKNOWN] (trust could not be evaluated — NEVER trusted/rejected), and
 * an absent/`null` signal defaults to UNKNOWN (never TRUSTED, never absence). The badge is ALWAYS one of the 5 states;
 * malformed is surfaced SEPARATELY (see [descriptorUpstreamError]), never as a 6th value.
 */
fun hubTrustBadgeState(trust: HubTrustState?, validity: HubDescriptorValidity): HubTrustState =
    if (validity == HubDescriptorValidity.MALFORMED) HubTrustState.UNKNOWN else (trust ?: HubTrustState.UNKNOWN)

/** The SEPARATE upstream-error signal — present (`⚠`) iff the descriptor is [HubDescriptorValidity.MALFORMED]. Its own
 *  namespace (never `hubTrust.*` trust-state), mandatory on malformed so a corrupt/hostile descriptor is never lost. */
fun descriptorUpstreamError(validity: HubDescriptorValidity): Boolean = validity == HubDescriptorValidity.MALFORMED

/** `testTag` contract for the per-hub trust badge — parity with web-ts `hub.trust.{hubId}.{state}` (Compose form `hubTrust.<hubId>.<state>`). */
object HubTrustTags {
    /** The badge container. */
    fun area(hubId: String) = "hubTrust.$hubId"

    /** The present-iff-state trust pill anchor: `hubTrust.<hubId>.<stateToken>` (stateToken = [HubTrustToneSpec.stateToken]). */
    fun state(hubId: String, stateToken: String) = "hubTrust.$hubId.$stateToken"

    /** The SEPARATE malformed-descriptor `⚠` marker — own namespace (NOT a trust state), mandatory on malformed. */
    fun upstreamError(hubId: String) = "hubTrust.$hubId.upstreamError"
}
