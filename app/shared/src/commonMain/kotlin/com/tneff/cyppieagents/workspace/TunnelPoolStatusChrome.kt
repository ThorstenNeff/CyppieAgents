package com.tneff.cyppieagents.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.connect.TunnelPoolStatusTags
import com.tneff.cyppieagents.eventlog.severityColor
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.net.hub.pool.TunnelPoolAggregate
import com.tneff.cyppieagents.net.hub.pool.TunnelPoolState
import com.tneff.cyppieagents.net.hub.pool.TunnelState
import com.tneff.cyppieagents.net.hub.pool.TunnelStatus
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_tunnel_pool_aggregate
import kmpcyppieagents.app.shared.generated.resources.a11y_tunnel_pool_row
import kmpcyppieagents.app.shared.generated.resources.tunnel_pool_aggregate
import kmpcyppieagents.app.shared.generated.resources.tunnel_pool_aggregate_backpressured
import kmpcyppieagents.app.shared.generated.resources.tunnel_pool_state_backpressured
import kmpcyppieagents.app.shared.generated.resources.tunnel_pool_state_dialing
import kmpcyppieagents.app.shared.generated.resources.tunnel_pool_state_down
import kmpcyppieagents.app.shared.generated.resources.tunnel_pool_state_up
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-540 (WS5) — the **N-tunnel pool-status chrome**: ONE workspace-scoped surface that HONESTLY renders the
 * frozen C3 [TunnelPoolState] the transport emits (per-tunnel DIALING/UP/BACKPRESSURED/DOWN + the pool aggregate).
 * Co-located BELOW [RemoteOperatingChrome] (spec §5.1 — one surface, per-tunnel rows inside; NOT N scattered
 * per-agent chips, mirrors the H4 one-relay-drop-surface precedent). It **renders** state; it never invents it.
 *
 * **Fail-closed / honesty invariants (spec §4, load-bearing — our derivation-masquerading specialty):**
 *  - INERT: [poolState]==null (Local / pre-connect / no emitter) OR 0 emitted tunnels ⇒ the whole surface is
 *    **absent** — never a phantom "0/cap" placeholder (that would read as "all down" = a false alarm).
 *  - Per-tunnel state = a **presence-marker** ([TunnelPoolStatusTags.tunnelState]); exactly one per row, only on a
 *    real emitter state — never optimistic `up`.
 *  - **UP is neutral, never success-green** (baseline, not a health guarantee); **BACKPRESSURED is amber-WARN,
 *    never red, never silently shown as plain UP**; **DOWN is offline-grey, never a red alarm** (a single tunnel
 *    down in a headroom pool is not a system error — the catastrophe "no live tunnel at all" stays fail-closed in
 *    the transport + the existing H4 relay-drop surface, §5.3; the pool never invents a second red).
 *  - The aggregate is **literal** (`active/cap`, `active<cap` ≠ deficit) with an amber WARN qualifier present-iff a
 *    **real** `anyBackpressured` flag — no green healthy-badge, no phantom positive.
 *
 * Colour is **never** the sole signal (WCAG 1.4.1): every state = tone **+ glyph + text label**. Tones reuse the
 * established `severityColor`/`colorScheme` seam (0 new hex).
 */
@Composable
fun TunnelPoolStatusChrome(
    poolState: StateFlow<TunnelPoolState>?,
    modifier: Modifier = Modifier,
) {
    // INERT: no emitter flow (Local / pre-connect) → absent (fail-closed, §4.4). Never a phantom placeholder.
    poolState ?: return
    val state by poolState.collectAsState()
    // INERT: connected but 0 tunnels emitted yet → still absent (no phantom "0/cap" that would read as "all down", §5.2).
    if (state.tunnels.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(TunnelPoolStatusTags.CONTAINER),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PoolAggregateRow(state.aggregate)
        // scopeId = the stable 0-based pool-slot INDEX (freeze decision ①), NOT the opaque rendezvousId.
        state.tunnels.forEachIndexed { slot, tunnel -> TunnelStatusRow(slot = slot, tunnel = tunnel) }
    }
}

/**
 * The aggregate summary (spec §3): literal `active/cap`, neutral baseline; the amber WARN qualifier
 * ([TunnelPoolStatusTags.AGGREGATE_BACKPRESSURED]) is present-iff a REAL `anyBackpressured` flag — the load-bearing
 * reason a throttled pool never reads as healthy even when every row looks "up".
 */
@Composable
private fun PoolAggregateRow(aggregate: TunnelPoolAggregate) {
    val backpressured = aggregate.anyBackpressured
    val summary = stringResource(Res.string.tunnel_pool_aggregate, aggregate.active, aggregate.cap)
    val qualifier = stringResource(Res.string.tunnel_pool_aggregate_backpressured)
    // %3$s = ", <qualifier>" iff backpressured; empty otherwise (honest — no trailing when the pool is not throttled).
    val a11ySuffix = if (backpressured) ", $qualifier" else ""
    val a11y = stringResource(Res.string.a11y_tunnel_pool_aggregate, aggregate.active, aggregate.cap, a11ySuffix)
    // Baseline neutral; amber ONLY on real backpressure — never a green healthy-tone (Anti-Hype).
    val summaryColor = if (backpressured) severityColor(Severity.WARN) else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TunnelPoolStatusTags.AGGREGATE)
            .semantics(mergeDescendants = true) {
                contentDescription = a11y
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(summary, color = summaryColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        // WARN qualifier — present-iff a REAL anyBackpressured flag (amber). Absent ⇔ false (fail-closed, not green-OK).
        if (backpressured) {
            Text(
                qualifier,
                color = severityColor(Severity.WARN),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag(TunnelPoolStatusTags.AGGREGATE_BACKPRESSURED),
            )
        }
    }
}

/**
 * One per-tunnel row (spec §2). scopeId = [slot] (freeze decision ①). The state is a single presence-marker
 * ([TunnelPoolStatusTags.tunnelState]) carrying glyph + label (WCAG 1.4.1); the rendezvousId rides its own node as
 * correlation CONTENT (freeze decision ②), never a tag segment.
 */
@Composable
private fun TunnelStatusRow(slot: Int, tunnel: TunnelStatus) {
    val segment = tunnel.state.tagSegment()
    val label = tunnel.state.label()
    val glyphColor = tunnel.state.glyphColor()
    val textColor = tunnel.state.textColor()
    val glyph = tunnel.state.glyph()
    val a11y = stringResource(Res.string.a11y_tunnel_pool_row, tunnel.rendezvousId, label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TunnelPoolStatusTags.tunnel(slot))
            .semantics(mergeDescendants = true) {
                contentDescription = a11y
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // State presence-marker: EXACTLY ONE per row. Glyph (colour never the sole carrier, WCAG 1.4.1) + label.
        Row(
            modifier = Modifier
                .weight(1f)
                .testTag(TunnelPoolStatusTags.tunnelState(slot, segment)),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(glyph, color = glyphColor, style = MaterialTheme.typography.bodyMedium)
            Text(label, color = textColor, style = MaterialTheme.typography.bodySmall)
        }
        // rendezvousId as correlation CONTENT (never a tag segment — opaque, not charset-safe). WS4 asserts this.
        Text(
            tunnel.rendezvousId,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.testTag(TunnelPoolStatusTags.tunnelRid(slot)),
        )
    }
}

/** The testTag state segment for each C3 state — the {dialing, up, backpressured, down} vocabulary (frozen). */
private fun TunnelState.tagSegment(): String = when (this) {
    TunnelState.DIALING -> "dialing"
    TunnelState.UP -> "up"
    TunnelState.BACKPRESSURED -> "backpressured"
    TunnelState.DOWN -> "down"
}

/**
 * Per-state GLYPH tone — the accent carrier (a glyph is a graphical object, WCAG 1.4.11 ≥3:1, so the accent tones
 * are safe here even where they would fail as text). Reuses the established `severityColor`/`colorScheme` seam
 * (0 new hex; the same glyph=accent pattern as [RemoteContextBanner]). DIALING = brand in-progress (never success);
 * UP = present neutral (NEVER green); BACKPRESSURED = amber WARN (never red / never silent-UP); DOWN = receded/muted
 * (never a red alarm).
 */
@Composable
private fun TunnelState.glyphColor(): Color = when (this) {
    TunnelState.DIALING -> MaterialTheme.colorScheme.primary
    TunnelState.UP -> MaterialTheme.colorScheme.onSurface
    TunnelState.BACKPRESSURED -> severityColor(Severity.WARN)
    TunnelState.DOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Per-state LABEL tone — **AA-safe as text** (WCAG 1.4.3 ≥4.5:1). `primary` (DIALING glyph) is NOT AA as text, so
 * the DIALING label rides the neutral `onSurface` (glyph-only accent, exactly like [RemoteContextBanner] B3). WARN
 * amber is the established AA-safe warning text tone; `onSurface`/`onSurfaceVariant` are AA (8.69:1/9.80:1). NEVER
 * `outline` for text (CYP-337: 3.55:1/3.63:1 fails 1.4.3 — its literal value is the `state.offline` grey, so that
 * token can only ever be a border/glyph tone, never a text colour). DOWN recedes via `onSurfaceVariant`, not colour-red.
 */
@Composable
private fun TunnelState.textColor(): Color = when (this) {
    TunnelState.DIALING -> MaterialTheme.colorScheme.onSurface
    TunnelState.UP -> MaterialTheme.colorScheme.onSurface
    TunnelState.BACKPRESSURED -> severityColor(Severity.WARN)
    TunnelState.DOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Per-state glyph — the non-colour carrier (WCAG 1.4.1). DIALING = a **static** dotted ring (reduced-motion-safe;
 * no endless spinner — animation is a deferred nicety, not an honesty signal); UP = filled dot; BACKPRESSURED = the
 * WARN triangle (matches `Severity.WARN.glyph()`); DOWN = a dash (offline).
 */
private fun TunnelState.glyph(): String = when (this) {
    TunnelState.DIALING -> "◌"
    TunnelState.UP -> "●"
    TunnelState.BACKPRESSURED -> "▲"
    TunnelState.DOWN -> "—"
}

/** Per-state text label (localized; §8 resource keys). */
@Composable
private fun TunnelState.label(): String = stringResource(
    when (this) {
        TunnelState.DIALING -> Res.string.tunnel_pool_state_dialing
        TunnelState.UP -> Res.string.tunnel_pool_state_up
        TunnelState.BACKPRESSURED -> Res.string.tunnel_pool_state_backpressured
        TunnelState.DOWN -> Res.string.tunnel_pool_state_down
    },
)
