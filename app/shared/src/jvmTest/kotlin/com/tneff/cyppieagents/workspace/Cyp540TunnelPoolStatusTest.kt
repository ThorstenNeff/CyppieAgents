package com.tneff.cyppieagents.workspace

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.connect.TunnelPoolStatusTags
import com.tneff.cyppieagents.net.hub.pool.TunnelPoolAggregate
import com.tneff.cyppieagents.net.hub.pool.TunnelPoolState
import com.tneff.cyppieagents.net.hub.pool.TunnelState
import com.tneff.cyppieagents.net.hub.pool.TunnelStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test

/**
 * CYP-540 (WS5) — the N-tunnel pool-status chrome: the frozen present-iff guards + honesty invariants (spec §4).
 * Drives [TunnelPoolStatusChrome] directly (hermetic render test) against the FROZEN [TunnelPoolStatusTags].
 *
 * Honesty is asserted on the **presence-markers** (locale-independent testTags — the load-bearing signal the tags
 * contract froze precisely so assertions are robust) plus the state GLYPH (`●`/`▲`/`◌`/`—`, also locale-independent)
 * for the WCAG 1.4.1 "colour is never the sole carrier" signal.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp540TunnelPoolStatusTest {

    private fun tunnel(rid: String, state: TunnelState) =
        TunnelStatus(rendezvousId = rid, state = state, sinceTs = 0L)

    private fun pool(
        vararg tunnels: TunnelStatus,
        active: Int = tunnels.count { it.state != TunnelState.DOWN },
        cap: Int = 12,
        anyBackpressured: Boolean = tunnels.any { it.state == TunnelState.BACKPRESSURED },
    ) = MutableStateFlow(
        TunnelPoolState(tunnels = tunnels.toList(), aggregate = TunnelPoolAggregate(active, cap, anyBackpressured)),
    )

    // --- INERT: no emitter flow → the whole surface is absent (fail-closed, §4.4) ---
    @Test
    fun inert_nullFlow_absent() = runComposeUiTest {
        setContent { MaterialTheme { TunnelPoolStatusChrome(poolState = null) } }
        onNodeWithTag(TunnelPoolStatusTags.CONTAINER, useUnmergedTree = true).assertDoesNotExist()
    }

    // --- INERT: connected but 0 tunnels emitted → STILL absent (no phantom "0/cap" that would read as "all down") ---
    @Test
    fun inert_emptyTunnels_absent() = runComposeUiTest {
        setContent { MaterialTheme { TunnelPoolStatusChrome(poolState = pool(active = 0, cap = 12, anyBackpressured = false)) } }
        onNodeWithTag(TunnelPoolStatusTags.CONTAINER, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(TunnelPoolStatusTags.AGGREGATE, useUnmergedTree = true).assertDoesNotExist()
    }

    // --- Present: ≥1 emitted tunnel → container + aggregate + the row present ---
    @Test
    fun present_containerAggregateRow() = runComposeUiTest {
        setContent { MaterialTheme { TunnelPoolStatusChrome(poolState = pool(tunnel("r0", TunnelState.UP))) } }
        onNodeWithTag(TunnelPoolStatusTags.CONTAINER, useUnmergedTree = true).assertExists()
        onNodeWithTag(TunnelPoolStatusTags.AGGREGATE, useUnmergedTree = true).assertExists()
        onNodeWithTag(TunnelPoolStatusTags.tunnel(0), useUnmergedTree = true).assertExists()
        onNodeWithTag(TunnelPoolStatusTags.tunnelRid(0), useUnmergedTree = true).assertExists()
    }

    // --- §4.1: UP → up marker present, neutral `●` glyph (NEVER a WARN/red form) ---
    @Test
    fun up_marker_neutralGlyph() = runComposeUiTest {
        setContent { MaterialTheme { TunnelPoolStatusChrome(poolState = pool(tunnel("r0", TunnelState.UP))) } }
        onNodeWithTag(TunnelPoolStatusTags.tunnelState(0, "up"), useUnmergedTree = true).assertExists()
        onNodeWithText("●", useUnmergedTree = true).assertExists()
        onNodeWithText("▲", useUnmergedTree = true).assertDoesNotExist() // UP is never a WARN
    }

    // --- §4.1: DIALING → dialing marker present, `up` ABSENT (never optimistic pre-render of UP) ---
    @Test
    fun dialing_marker_notOptimisticUp() = runComposeUiTest {
        setContent { MaterialTheme { TunnelPoolStatusChrome(poolState = pool(tunnel("r0", TunnelState.DIALING))) } }
        onNodeWithTag(TunnelPoolStatusTags.tunnelState(0, "dialing"), useUnmergedTree = true).assertExists()
        onNodeWithTag(TunnelPoolStatusTags.tunnelState(0, "up"), useUnmergedTree = true).assertDoesNotExist()
    }

    // --- §4.2 (load-bearing): BACKPRESSURED → backpressured marker + amber-WARN `▲`; NEVER silently `up`, NEVER `●` ---
    @Test
    fun backpressured_warn_neverSilentUp() = runComposeUiTest {
        setContent { MaterialTheme { TunnelPoolStatusChrome(poolState = pool(tunnel("r0", TunnelState.BACKPRESSURED))) } }
        onNodeWithTag(TunnelPoolStatusTags.tunnelState(0, "backpressured"), useUnmergedTree = true).assertExists()
        onNodeWithTag(TunnelPoolStatusTags.tunnelState(0, "up"), useUnmergedTree = true).assertDoesNotExist() // never silent-UP
        onNodeWithText("▲", useUnmergedTree = true).assertExists() // WARN glyph (amber, never red)
        onNodeWithText("●", useUnmergedTree = true).assertDoesNotExist()
    }

    // --- §4.3: DOWN → down marker present, offline `—` (honest down; not hidden, not a red alarm) ---
    @Test
    fun down_marker_honest() = runComposeUiTest {
        setContent { MaterialTheme { TunnelPoolStatusChrome(poolState = pool(tunnel("r0", TunnelState.DOWN))) } }
        onNodeWithTag(TunnelPoolStatusTags.tunnelState(0, "down"), useUnmergedTree = true).assertExists()
        onNodeWithTag(TunnelPoolStatusTags.tunnelState(0, "up"), useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("—", useUnmergedTree = true).assertExists()
    }

    // --- Freeze decision ②: EXACTLY ONE state marker per row (the other three absent) ---
    @Test
    fun exactlyOneStateMarker_perRow() = runComposeUiTest {
        setContent { MaterialTheme { TunnelPoolStatusChrome(poolState = pool(tunnel("r0", TunnelState.BACKPRESSURED))) } }
        onNodeWithTag(TunnelPoolStatusTags.tunnelState(0, "backpressured"), useUnmergedTree = true).assertExists()
        onNodeWithTag(TunnelPoolStatusTags.tunnelState(0, "up"), useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(TunnelPoolStatusTags.tunnelState(0, "dialing"), useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(TunnelPoolStatusTags.tunnelState(0, "down"), useUnmergedTree = true).assertDoesNotExist()
    }

    // --- §4.5: aggregate WARN qualifier present ⇔ a REAL anyBackpressured flag (true → present) ---
    @Test
    fun aggregate_backpressured_present_iff_flag_true() = runComposeUiTest {
        setContent {
            MaterialTheme {
                // A row that itself looks "up", but the pool aggregate is honestly backpressured (flag independent of rows).
                TunnelPoolStatusChrome(poolState = pool(tunnel("r0", TunnelState.UP), anyBackpressured = true))
            }
        }
        onNodeWithTag(TunnelPoolStatusTags.AGGREGATE_BACKPRESSURED, useUnmergedTree = true).assertExists()
    }

    // --- §4.5 (fail-closed): flag false → qualifier ABSENT (no phantom positive, no green-OK) ---
    @Test
    fun aggregate_backpressured_absent_iff_flag_false() = runComposeUiTest {
        setContent {
            MaterialTheme {
                TunnelPoolStatusChrome(poolState = pool(tunnel("r0", TunnelState.UP), anyBackpressured = false))
            }
        }
        onNodeWithTag(TunnelPoolStatusTags.AGGREGATE, useUnmergedTree = true).assertExists()
        onNodeWithTag(TunnelPoolStatusTags.AGGREGATE_BACKPRESSURED, useUnmergedTree = true).assertDoesNotExist()
    }

    // --- Freeze decision ①: scopeId = the 0-based SLOT INDEX (not rid); rid rides as content on its own node ---
    @Test
    fun scopeId_isSlotIndex_ridIsContent() = runComposeUiTest {
        setContent {
            MaterialTheme {
                TunnelPoolStatusChrome(
                    poolState = pool(
                        tunnel("opaque-rid-AAA", TunnelState.UP),
                        tunnel("opaque-rid-BBB", TunnelState.DOWN),
                    ),
                )
            }
        }
        // Rows are keyed on the index — both slots addressable regardless of the opaque rids.
        onNodeWithTag(TunnelPoolStatusTags.tunnel(0), useUnmergedTree = true).assertExists()
        onNodeWithTag(TunnelPoolStatusTags.tunnel(1), useUnmergedTree = true).assertExists()
        // The rid is CONTENT on the .rid node (correlation), never a tag segment.
        onNodeWithTag(TunnelPoolStatusTags.tunnelRid(0), useUnmergedTree = true).assertExists()
        onNodeWithText("opaque-rid-AAA", useUnmergedTree = true).assertExists()
        onNodeWithText("opaque-rid-BBB", useUnmergedTree = true).assertExists()
        // Per-slot state markers stay correct across rows.
        onNodeWithTag(TunnelPoolStatusTags.tunnelState(0, "up"), useUnmergedTree = true).assertExists()
        onNodeWithTag(TunnelPoolStatusTags.tunnelState(1, "down"), useUnmergedTree = true).assertExists()
    }
}
