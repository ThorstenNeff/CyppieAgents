package com.tneff.cyppieagents.window

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * The "desktop" window host: a full-size surface that stacks every window in [state] according to
 * its z-order (list order). Each window's body is supplied by [windowContent], so the host is fully
 * decoupled from what lives inside a window — CYP-6's renderer plugs in there later without changing
 * this code.
 */
@Composable
fun WindowHost(
    state: WindowManagerState,
    modifier: Modifier = Modifier,
    windowContent: @Composable (WindowState) -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(WindowTestTags.HOST),
    ) {
        state.windows.forEachIndexed { index, window ->
            // Key by id so a window keeps its identity (and any internal state) when the list is
            // reordered on focus; graphicsLayer below applies the z-order from the list index.
            key(window.id) {
                FloatingWindow(
                    window = window,
                    isFocused = window.id == state.focusedId,
                    zOrder = index.toFloat(),
                    onFocus = { state.focus(window.id) },
                    onMove = { dx, dy -> state.moveBy(window.id, dx, dy) },
                    onResize = { dWidth, dHeight -> state.resizeBy(window.id, dWidth, dHeight) },
                    content = { windowContent(window) },
                )
            }
        }
    }
}

/**
 * A single floating window: a positioned, sized surface with a draggable title bar, a content slot
 * and a bottom-end resize grip. All geometry callbacks report deltas in dp.
 *
 * @param zOrder stacking order; higher draws on top. Set from the host's list index.
 * @param onFocus invoked when the window is pressed or a drag/resize gesture starts.
 * @param onMove drag delta of the title bar, in dp.
 * @param onResize drag delta of the resize grip, in dp.
 */
@Composable
fun FloatingWindow(
    window: WindowState,
    isFocused: Boolean,
    zOrder: Float,
    onFocus: () -> Unit,
    onMove: (dx: Float, dy: Float) -> Unit,
    onResize: (dWidth: Float, dHeight: Float) -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            // Position via graphicsLayer translation + zIndex through the same layer so reordering on
            // focus does not require re-laying-out siblings.
            .graphicsLayer {
                translationX = window.x.dp.toPx()
                translationY = window.y.dp.toPx()
            }
            .zIndex(zOrder)
            .size(window.width.dp, window.height.dp)
            .testTag(WindowTestTags.window(window.id))
            // Pressing anywhere on the window raises it. Initial pass + no consume so child drag
            // handlers still receive the gesture; onFocus is idempotent for the already-front window.
            .pointerInput(window.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.changedToDownIgnoreConsumed() }) onFocus()
                    }
                }
            },
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = if (isFocused) 6.dp else 1.dp,
            shadowElevation = if (isFocused) 12.dp else 2.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Title bar — drag to move.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isFocused) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .testTag(WindowTestTags.titleBar(window.id))
                        .pointerInput(window.id) {
                            detectDragGestures(
                                onDragStart = { onFocus() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onMove(dragAmount.x.toDp().value, dragAmount.y.toDp().value)
                                },
                            )
                        },
                ) {
                    Text(
                        text = window.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isFocused) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }

                // Content slot — arbitrary composable supplied by the host.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(WindowTestTags.content(window.id)),
                ) {
                    content()
                }
            }
        }

        // Resize grip — drag to resize from the bottom-end corner.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(20.dp)
                .clip(RoundedCornerShape(topStart = 8.dp))
                .background(MaterialTheme.colorScheme.secondary)
                .testTag(WindowTestTags.resizeHandle(window.id))
                .pointerInput(window.id) {
                    detectDragGestures(
                        onDragStart = { onFocus() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onResize(dragAmount.x.toDp().value, dragAmount.y.toDp().value)
                        },
                    )
                },
        )
    }
}
