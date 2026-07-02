package com.tneff.cyppieagents

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.window

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport {
        // CYP-185: navigate the page to the GitHub OIDC URL — the platform-actual for §6. A full-page
        // redirect (same-origin proxy) so GitHub's callback returns to the app, which re-boots into session().
        App(onOpenExternalUrl = { url -> window.location.href = url })
    }
}