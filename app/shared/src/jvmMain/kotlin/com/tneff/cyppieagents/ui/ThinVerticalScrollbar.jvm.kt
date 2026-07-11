package com.tneff.cyppieagents.ui

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** CYP-392 — PLATFORM skiko draw: the Compose-foundation VerticalScrollbar styled per the commonMain decisions. */
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
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(listState),
        modifier = modifier,
        style = ScrollbarStyle(
            minimalHeight = minimalHeight,
            thickness = thickness,
            shape = RoundedCornerShape(cornerRadius),
            hoverDurationMillis = 300,
            unhoverColor = restColor,
            hoverColor = activeColor,
        ),
    )
}
