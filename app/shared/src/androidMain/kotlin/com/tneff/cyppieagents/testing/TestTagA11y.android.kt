package com.tneff.cyppieagents.testing

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

actual fun Modifier.testTagA11y(tag: String): Modifier = this.testTag(tag)
