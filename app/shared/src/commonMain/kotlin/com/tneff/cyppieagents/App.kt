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
import com.tneff.cyppieagents.auth.authRepositoryFor
import com.tneff.cyppieagents.auth.defaultAuthLiveEnv
import com.tneff.cyppieagents.auth.resolveAuthMode
import com.tneff.cyppieagents.testing.enableTestTagsAsResourceId

@Composable
@Preview
fun App(
    authRepository: AuthRepository? = null,
    // CYP-185: platform hook to open the GitHub OIDC redirect URL externally (§6 — the OAuth dance stays out
    // of commonMain). Default no-op; a platform entry point wires the real open (Desktop.browse / window nav).
    onOpenExternalUrl: (String) -> Unit = {},
) {
    // CYP-176: the login gate wraps the existing desktop (auth-spec §8.1) — it renders the auth screens
    // until AuthState == Verified, then mounts AgentShell unchanged (CYP-15). enableTestTagsAsResourceId()
    // sits at the gate root so Maestro can address both the auth screens and the desktop subtree (CYP-11).
    //
    // CYP-182 config-gated flip: [authRepository] overrides for tests; otherwise the repository is chosen by
    // the auth-live config (CYP-182 flip, client GO 2026-07-02) — CYPPIE_AUTH_LIVE + stack URLs → the live
    // HttpAuthRepository; absent → the CYP-177 StubAuthRepository (Dev/Demo default, zero blast-radius); flag
    // set with missing/malformed URLs → fail-loud (AuthConfigException at startup, never a silent stub
    // downgrade — "login live was intended"). No VM/UI change (the seam's point).
    val authRepo = remember(authRepository) {
        authRepository ?: authRepositoryFor(resolveAuthMode(defaultAuthLiveEnv()))
    }
    val authViewModel = remember(authRepo) { AuthViewModel(authRepo) }
    MaterialTheme {
        AuthGate(
            viewModel = authViewModel,
            modifier = Modifier
                .enableTestTagsAsResourceId()
                .safeContentPadding()
                .fillMaxSize(),
            onOpenExternalUrl = onOpenExternalUrl,
        ) { tier ->
            // CYP-186: the verified user's tier gates the desktop's operator surfaces (hybrid: role OR token).
            // CYP-188: thread the session credential so a session-only user (Kratos login, no operator token)
            // authenticates the shell's data reads/sockets (X-Session-Token native / same-origin cookie browser).
            AgentShell(
                modifier = Modifier.fillMaxSize(),
                tier = tier,
                sessionToken = authRepo::currentSessionToken,
            )
        }
    }
}
