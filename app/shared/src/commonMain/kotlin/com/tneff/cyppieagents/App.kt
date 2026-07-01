package com.tneff.cyppieagents

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tneff.cyppieagents.auth.AuthGate
import com.tneff.cyppieagents.auth.AuthViewModel
import com.tneff.cyppieagents.auth.StubAuthRepository
import com.tneff.cyppieagents.testing.enableTestTagsAsResourceId

@Composable
@Preview
fun App() {
    // CYP-176: the login gate wraps the existing desktop (auth-spec §8.1) — it renders the auth screens
    // until AuthState == Verified, then mounts AgentShell unchanged (CYP-15). Today it runs against the
    // StubAuthRepository seam; the live REST impl swaps in behind the same seam (no VM/UI change).
    // enableTestTagsAsResourceId() sits at the gate root so Maestro can address both the auth screens and
    // the desktop subtree (CYP-11).
    val authViewModel = remember { AuthViewModel(StubAuthRepository()) }
    MaterialTheme {
        AuthGate(
            viewModel = authViewModel,
            modifier = Modifier
                .enableTestTagsAsResourceId()
                .safeContentPadding()
                .fillMaxSize(),
        ) {
            AgentShell(modifier = Modifier.fillMaxSize())
        }
    }
}
