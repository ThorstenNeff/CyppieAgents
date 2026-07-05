package com.tneff.cyppieagents.agentsettings

import androidx.compose.runtime.Composable

/** iOS actual — a **stub**, consistent with the iOS baseline (the UIKit paths are stubbed too). No custom upload. */
@Composable
actual fun rememberImagePicker(onPicked: (PickedImage) -> Unit): () -> Unit = {}
