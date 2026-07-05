package com.tneff.cyppieagents.agentsettings

import androidx.compose.runtime.Composable

/** A picked image's raw bytes + metadata for the client pre-check + multipart upload (CYP-216). */
class PickedImage(val bytes: ByteArray, val filename: String, val mimeType: String)

/**
 * CYP-216 — the platform image picker. Returns a launch lambda; calling it opens the platform file dialog and, on a
 * successful pick, invokes [onPicked] with the bytes. The picker restricts to PNG/JPG where the platform allows; the
 * VM re-checks type+size and the SERVER is authoritative. Targets (PO 2026-07-05): **wasmJs** `<input type=file>`
 * (the deploy-critical web SPA) + **jvm** `FileDialog` are real; **android** is a compiling stub (custom upload →
 * follow-up CYP-222; the primary preset path works there) and **ios** is a stub (iOS baseline).
 */
@Composable
expect fun rememberImagePicker(onPicked: (PickedImage) -> Unit): () -> Unit
