package com.tneff.cyppieagents.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// CYP-392 §1 — the house's ONE thin-scrollbar dimensions (so Comm can reuse the exact style later, no divergence).
val THIN_SCROLLBAR_THICKNESS: Dp = 8.dp        // fits INSIDE the transcript's 12dp contentPadding → no text overlap
val THIN_SCROLLBAR_CORNER: Dp = 4.dp           // pill = thickness/2
val THIN_SCROLLBAR_MIN_HEIGHT: Dp = 24.dp      // a grabbable thumb on a very long list (≥ WCAG 2.5.8 target)

/**
 * CYP-392 — the reusable thin vertical scrollbar (spec §0: one shared style; the Comm timeline adopts it later
 * with one line). **The style DECISIONS live here in commonMain** — colours are design-system roles (§2), dims
 * are the constants above — so the contrast / no-new-colour guards have one place to scan.
 *
 * **A target seam is REQUIRED** (not optional as the spec first assumed): `VerticalScrollbar` /
 * `rememberScrollbarAdapter` are skiko-only and are **unresolved in commonMain** while `:app:shared` also targets
 * **Android** (proven: `compileCommonMainKotlinMetadata` fails with "Unresolved reference 'VerticalScrollbar'").
 * So the actual draw is delegated to [renderThinScrollbar]: real on jvm/js/wasmJs, a no-op on android/ios (which
 * scroll with native affordances). The Z-order caveat from 04 §5 is about the *terminal* overlay, not this pure
 * Compose overlay.
 *
 * §2 colours: rest = `outline` (≥3:1 on `surface` in both themes; **no alpha** — the CYP-337 lesson, `outline`
 * sits near the 3:1 floor), active (hover/drag) = `onSurfaceVariant`. Placement/visibility (§3) is the caller's.
 */
@Composable
fun ThinVerticalScrollbar(listState: LazyListState, modifier: Modifier = Modifier) {
    renderThinScrollbar(
        listState = listState,
        thickness = THIN_SCROLLBAR_THICKNESS,
        cornerRadius = THIN_SCROLLBAR_CORNER,
        minimalHeight = THIN_SCROLLBAR_MIN_HEIGHT,
        restColor = MaterialTheme.colorScheme.outline,
        activeColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Per-target skiko draw of the styled scrollbar (real on jvm/js/wasmJs; no-op on android/ios). */
@Composable
internal expect fun renderThinScrollbar(
    listState: LazyListState,
    thickness: Dp,
    cornerRadius: Dp,
    minimalHeight: Dp,
    restColor: Color,
    activeColor: Color,
    modifier: Modifier,
)
