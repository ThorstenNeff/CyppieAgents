package com.tneff.cyppieagents.window

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
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
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag(WindowTestTags.HOST),
    ) {
        // Report the measured host size so the state can keep windows within the visible area.
        val hostWidthDp = maxWidth.value
        val hostHeightDp = maxHeight.value
        LaunchedEffect(hostWidthDp, hostHeightDp) {
            state.updateHostSize(hostWidthDp, hostHeightDp)
        }

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
 * @param onFocus invoked when the window is pressed, keyboard-focused, or a drag/resize starts.
 * @param onMove drag/keyboard delta to move the window, in dp.
 * @param onResize drag/keyboard delta to resize the window, in dp.
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
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
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
            // Screenreader: expose the window as a navigable heading (WCAG 1.3.1/4.1.2). testTag
            // stays separate for tests.
            .semantics {
                heading()
                contentDescription = "Agentenfenster ${window.title}"
            }
            // Keyboard operation (WCAG 2.1.1): Tab focuses the window; arrows move it, Shift+arrows
            // resize it. Focusing also raises it. Move/resize go through the same clamped callbacks.
            .onFocusChanged { if (it.isFocused) onFocus() }
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val resize = event.isShiftPressed
                when (event.key) {
                    Key.DirectionLeft -> {
                        if (resize) onResize(-KEYBOARD_RESIZE_STEP, 0f) else onMove(-KEYBOARD_MOVE_STEP, 0f)
                        true
                    }
                    Key.DirectionRight -> {
                        if (resize) onResize(KEYBOARD_RESIZE_STEP, 0f) else onMove(KEYBOARD_MOVE_STEP, 0f)
                        true
                    }
                    Key.DirectionUp -> {
                        if (resize) onResize(0f, -KEYBOARD_RESIZE_STEP) else onMove(0f, -KEYBOARD_MOVE_STEP)
                        true
                    }
                    Key.DirectionDown -> {
                        if (resize) onResize(0f, KEYBOARD_RESIZE_STEP) else onMove(0f, KEYBOARD_MOVE_STEP)
                        true
                    }
                    else -> false
                }
            }
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
                        .semantics { contentDescription = "Titelleiste ${window.title}, mit Pfeiltasten verschieben" }
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

        // Resize grip — drag from the bottom-end corner. The hit area is RESIZE_HIT_SLOP (44dp) for a
        // comfortable touch target (WCAG 2.5.8); the visible grip is RESIZE_HANDLE_SIZE (24dp).
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(RESIZE_HIT_SLOP.dp)
                .testTag(WindowTestTags.resizeHandle(window.id))
                .semantics { contentDescription = "Größe ändern, ${window.title}" }
                .pointerInput(window.id, isRtl) {
                    detectDragGestures(
                        onDragStart = { onFocus() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            // RTL: the grip sits at the visual start, so mirror the horizontal delta.
                            val dWidth = WindowReducer.resizeDeltaForLayout(dragAmount.x.toDp().value, isRtl)
                            onResize(dWidth, dragAmount.y.toDp().value)
                        },
                    )
                },
            contentAlignment = Alignment.BottomEnd,
        ) {
            Box(
                modifier = Modifier
                    .size(RESIZE_HANDLE_SIZE.dp)
                    .clip(RoundedCornerShape(topStart = 8.dp))
                    .background(MaterialTheme.colorScheme.secondary),
            )
        }
    }
}
