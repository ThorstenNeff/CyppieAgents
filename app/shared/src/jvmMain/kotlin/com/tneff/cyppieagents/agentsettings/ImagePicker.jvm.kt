package com.tneff.cyppieagents.agentsettings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** JVM/desktop actual — AWT [FileDialog] (native modal). PNG/JPG only; the VM re-checks + the server is authoritative. */
@Composable
actual fun rememberImagePicker(onPicked: (PickedImage) -> Unit): () -> Unit {
    val current by rememberUpdatedState(onPicked)
    return {
        val dialog = FileDialog(null as Frame?, "Bild wählen (PNG oder JPG)", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.lowercase().let { it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") } }
        dialog.isVisible = true
        val dir = dialog.directory
        val name = dialog.file
        if (dir != null && name != null) {
            val f = File(dir, name)
            val mime = when (f.extension.lowercase()) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                else -> "application/octet-stream"
            }
            current(PickedImage(f.readBytes(), f.name, mime))
        }
    }
}
