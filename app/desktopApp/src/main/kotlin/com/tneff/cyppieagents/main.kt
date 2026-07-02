package com.tneff.cyppieagents

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.awt.Desktop
import java.net.URI

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "KMPCyppieAgents",
    ) {
        // CYP-185: open the GitHub OIDC redirect URL in the system browser — the platform-actual for §6
        // (commonMain only holds the Redirecting state). A headless/unsupported desktop is a safe no-op.
        App(
            onOpenExternalUrl = { url ->
                runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url)) }
            },
        )
    }
}