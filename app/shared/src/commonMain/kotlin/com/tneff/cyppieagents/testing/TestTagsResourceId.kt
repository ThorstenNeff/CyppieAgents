package com.tneff.cyppieagents.testing

import androidx.compose.ui.Modifier

/**
 * Exposes `Modifier.testTag` values as resource-ids so Maestro (Android UiAutomator, Wasm/Web) can
 * address them via `id:` (Test-Contract v0.5 §2/§4). Compose UI tests (`onNodeWithTag`) do NOT need
 * this — it is purely for the external Maestro/accessibility layer.
 *
 * Platform-specific: **Android** sets the real semantics property; other targets are currently
 * **no-ops** (Desktop/iOS have no resource-id layer Maestro consumes; the **Wasm** mechanism is
 * verified as part of the Wasm runtime spike by the tester).
 *
 * Apply ONCE at the app/window root — that root wiring lives in the shell ticket (CYP-15), not here.
 */
expect fun Modifier.enableTestTagsAsResourceId(): Modifier
