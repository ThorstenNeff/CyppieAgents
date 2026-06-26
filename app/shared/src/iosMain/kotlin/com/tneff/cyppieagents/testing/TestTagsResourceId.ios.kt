package com.tneff.cyppieagents.testing

import androidx.compose.ui.Modifier

// iOS has no resource-id layer Maestro consumes in MVP scope — no-op.
actual fun Modifier.enableTestTagsAsResourceId(): Modifier = this
