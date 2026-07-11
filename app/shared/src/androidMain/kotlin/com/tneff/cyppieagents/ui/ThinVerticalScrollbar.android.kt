package com.tneff.cyppieagents.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** CYP-392 — no-op: no Compose-foundation VerticalScrollbar here; native scrolling provides its own indicators. */
@Composable
internal actual fun renderThinScrollbar(
    listState: LazyListState,
    thickness: Dp,
    cornerRadius: Dp,
    minimalHeight: Dp,
    restColor: Color,
    activeColor: Color,
    modifier: Modifier,
) {
    // Intentionally empty.
}
