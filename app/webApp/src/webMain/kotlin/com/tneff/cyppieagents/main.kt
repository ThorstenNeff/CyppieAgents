package com.tneff.cyppieagents

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.tneff.cyppieagents.net.installCredentialsThenStart
import kotlinx.browser.window

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // CYP-243 hardening: patch window.fetch (same-origin credentials + CSRF echo) SYNCHRONOUSLY before the UI —
    // i.e. before ANY Ktor client is created, including the pre-shell auth-probe client (GET /api/auth/me) that
    // otherwise fires unwrapped. The shell's own install (sharedWsHttpClient) stays as an idempotent fallback.
    installCredentialsThenStart {
        ComposeViewport {
            // CYP-185: navigate the page to the GitHub OIDC URL — the platform-actual for §6. A full-page
            // redirect (same-origin proxy) so GitHub's callback returns to the app, which re-boots into session().
            App(onOpenExternalUrl = { url -> window.location.href = url })
        }
    }
}