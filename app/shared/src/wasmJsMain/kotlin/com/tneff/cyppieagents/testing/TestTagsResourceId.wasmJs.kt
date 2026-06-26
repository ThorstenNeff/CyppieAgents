package com.tneff.cyppieagents.testing

import androidx.compose.ui.Modifier

// Wasm/Web: the resource-id exposure mechanism for Maestro is verified in the Wasm runtime spike
// (tester). No-op until that lands, so the seam compiles and the root can already call it.
actual fun Modifier.enableTestTagsAsResourceId(): Modifier = this
