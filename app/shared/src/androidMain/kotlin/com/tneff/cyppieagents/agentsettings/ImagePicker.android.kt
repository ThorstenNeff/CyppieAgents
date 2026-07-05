package com.tneff.cyppieagents.agentsettings

import androidx.compose.runtime.Composable

/**
 * Android actual — a compiling **stub** (PO 2026-07-05). The deploy target is the web SPA, so custom-upload
 * byte-acquisition ships for wasmJs/jvm first; Android preset selection (the primary avatar path) already works.
 * Custom upload here is a no-op until follow-up **CYP-222** wires `ActivityResultContracts.GetContent` (needs the
 * `activity-compose` dependency) — deliberately not pulled in at the finish line for a non-deploy-critical path.
 */
@Composable
actual fun rememberImagePicker(onPicked: (PickedImage) -> Unit): () -> Unit = {}
