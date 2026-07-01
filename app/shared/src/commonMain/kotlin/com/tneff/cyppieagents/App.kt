package com.tneff.cyppieagents

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tneff.cyppieagents.auth.AuthGate
import com.tneff.cyppieagents.auth.AuthRepository
import com.tneff.cyppieagents.auth.AuthViewModel
import com.tneff.cyppieagents.auth.StubAuthRepository
import com.tneff.cyppieagents.testing.enableTestTagsAsResourceId

@Composable
@Preview
fun App(authRepository: AuthRepository? = null) {
    // CYP-176: the login gate wraps the existing desktop (auth-spec §8.1) — it renders the auth screens
    // until AuthState == Verified, then mounts AgentShell unchanged (CYP-15). enableTestTagsAsResourceId()
    // sits at the gate root so Maestro can address both the auth screens and the desktop subtree (CYP-11).
    //
    // CYP-182 real-swap seam: [authRepository] injects the live port. The DEFAULT stays the CYP-177
    // StubAuthRepository until the Kratos/Caddy deploy is live (P2.2) — the real HttpAuthRepository is
    // proven by hermetic tests and swaps in with NO VM/UI change (the seam's point) by passing e.g.
    //   HttpAuthRepository(sharedWsHttpClient(), defaultShellConfig().hubHttpBaseUrl)
    // here (or via this param) once the live endpoint/proxy is up (PO-gated final flip).
    val authRepo = remember(authRepository) { authRepository ?: StubAuthRepository() }
    val authViewModel = remember(authRepo) { AuthViewModel(authRepo) }
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
