package com.tneff.cyppieagents.agentview

/**
 * CYP-393 §2 — how far past the exact end still counts as "at the bottom" (~48 dp). Streaming deltas and the
 * animation landing sit a few pixels off exact, so an exact `!canScrollForward` test would flicker the pin. The
 * caller converts this to layout pixels at the current density.
 */
const val TRANSCRIPT_BOTTOM_TOLERANCE_DP: Int = 48

/**
 * CYP-393 — pure predicate: is the transcript scrolled to (within [tolerancePx] of) the live end? Extracted from
 * the composable so the tolerance boundary is unit-tested without a running scroll. All offsets are LazyColumn
 * layout PIXELS ([androidx.compose.foundation.lazy.LazyListLayoutInfo]).
 *
 *  - An empty list is "at the bottom" (nothing to scroll → the fresh view is pinned).
 *  - Otherwise: the LAST item must be visible (its index is the final one) AND its bottom edge must sit within
 *    the viewport end plus the tolerance.
 */
internal fun transcriptAtBottom(
    totalItems: Int,
    lastVisibleIndex: Int?,
    lastVisibleItemBottom: Int,
    viewportEndOffset: Int,
    tolerancePx: Int,
): Boolean {
    if (totalItems == 0) return true
    if (lastVisibleIndex == null) return false
    return lastVisibleIndex >= totalItems - 1 && lastVisibleItemBottom <= viewportEndOffset + tolerancePx
}
