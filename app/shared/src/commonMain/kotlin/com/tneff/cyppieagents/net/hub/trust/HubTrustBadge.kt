package com.tneff.cyppieagents.net.hub.trust

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.eventlog.severityColor
import com.tneff.cyppieagents.model.HubDescriptorValidity
import com.tneff.cyppieagents.model.HubTrustState
import com.tneff.cyppieagents.model.Severity
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.hub_descriptor_invalid
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-808 — the standalone **per-hub trust badge** (axis a, [HubTrustState]). The Compose twin of web-ts
 * `HubTrustBadge.tsx` (CYP-801): identical contract, prop-driven, renders ONE hub's trust honestly + non-alarmingly.
 * The colour is drawn ONLY from [hubTrustTone] (the single trust-tone source) — no inline colour here.
 *
 * **Two independent signals (never conflated — the honesty core, CYP-798 §4b):**
 *  1. **The trust PILL** — always one of the 5 [HubTrustState] states. Fail-closed: an absent/`null` [state] is UNKNOWN
 *     (never TRUSTED); a [HubDescriptorValidity.MALFORMED] descriptor collapses the pill to UNKNOWN (trust could not be
 *     evaluated), never rejected/trusted. testTag [HubTrustTags.state] (present-iff the current state).
 *  2. **The MALFORMED `⚠` upstream marker** — SEPARATE, in its own [HubTrustTags.upstreamError] namespace (never a
 *     trust state), ERROR-toned, rendered MANDATORILY whenever the descriptor is malformed so a corrupt/hostile
 *     descriptor is never silently lost. A descriptor error is NOT a trust verdict.
 *
 * **a11y:** the badge is persistent STATE, not a just-happened event → `liveRegion = Polite`. The active-hub Assertive
 * escalation is a PLACEMENT concern (where the badge sits in the chrome), not baked into this leaf. The a11y text
 * spells out the meaning WORD (never glyph/colour alone — WCAG 1.4.1). The `⚠` marker is a `role="alert"` analog.
 *
 * The REJECTED **reason** copy ([com.tneff.cyppieagents.model.TrustRejectReason], `remote_connect_trust_changed` /
 * `_rejected`) precises the message in the CONNECT FLOW arms (`HubConnectSelection`, CYP-797) — NOT in this leaf badge,
 * which shows the state WORD ("rejected"). Same as the web-ts sibling (no reason prop).
 */
@Composable
fun HubTrustBadge(
    hubId: String,
    state: HubTrustState?,
    modifier: Modifier = Modifier,
    /** The SEPARATE descriptor-validity axis. Default VALID = "not flagged malformed" (absence must not fabricate a ⚠). */
    validity: HubDescriptorValidity = HubDescriptorValidity.VALID,
) {
    val effectiveState = hubTrustBadgeState(state, validity)
    val tone = hubTrustTone(effectiveState)
    val label = stringResource(tone.labelKey)
    val a11y = stringResource(tone.a11yKey)

    Column(
        modifier = modifier
            .testTag(HubTrustTags.area(hubId))
            // Persistent state → announced calmly (never Assertive here; that's a placement concern).
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // The trust pill — the a11y text (the WORD, never glyph/colour alone) + the present-iff-state anchor.
        Row(
            modifier = Modifier
                .testTag(HubTrustTags.state(hubId, tone.stateToken))
                .semantics(mergeDescendants = true) { contentDescription = a11y },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tone.glyph, color = tone.color, style = MaterialTheme.typography.labelMedium)
            Text(label, color = tone.color, style = MaterialTheme.typography.labelMedium)
        }
        // The SEPARATE malformed-descriptor ⚠ marker — own namespace, ERROR tone, mandatory + never hidden on malformed.
        if (descriptorUpstreamError(validity)) {
            val invalid = stringResource(Res.string.hub_descriptor_invalid)
            Row(
                modifier = Modifier
                    .testTag(HubTrustTags.upstreamError(hubId))
                    .semantics(mergeDescendants = true) {
                        contentDescription = invalid
                        liveRegion = LiveRegionMode.Assertive // role="alert" analog — unsolicited, mandatory
                    },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⚠", color = severityColor(Severity.ERROR), style = MaterialTheme.typography.labelMedium)
                Text(invalid, color = severityColor(Severity.ERROR), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
