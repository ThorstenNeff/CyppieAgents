package com.tneff.cyppieagents.agentsettings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.browser.document
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File
import org.w3c.files.FileReader
import org.w3c.files.get

/** Kotlin/JS web actual — a hidden `<input type=file>`; reads the picked File to bytes via [FileReader]. */
@Composable
actual fun rememberImagePicker(onPicked: (PickedImage) -> Unit): () -> Unit {
    val current by rememberUpdatedState(onPicked)
    return {
        val input = document.createElement("input") as HTMLInputElement
        input.type = "file"
        input.accept = "image/png,image/jpeg"
        input.onchange = {
            val file: File? = input.files?.get(0)
            if (file != null) {
                val reader = FileReader()
                reader.onload = {
                    val buffer = reader.result as ArrayBuffer
                    val view = Int8Array(buffer)
                    current(PickedImage(ByteArray(view.length) { view[it] }, file.name, file.type))
                }
                reader.readAsArrayBuffer(file)
            }
        }
        input.click()
    }
}
