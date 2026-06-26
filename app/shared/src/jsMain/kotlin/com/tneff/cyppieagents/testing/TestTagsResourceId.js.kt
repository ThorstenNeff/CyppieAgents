package com.tneff.cyppieagents.testing

import androidx.compose.ui.Modifier

// JS/Web: no-op (same status as Wasm; resource-id exposure verified in the runtime spike).
actual fun Modifier.enableTestTagsAsResourceId(): Modifier = this
