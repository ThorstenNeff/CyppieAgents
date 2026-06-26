package com.tneff.cyppieagents.testing

import androidx.compose.ui.Modifier

// Desktop has no resource-id layer Maestro consumes — no-op.
actual fun Modifier.enableTestTagsAsResourceId(): Modifier = this
