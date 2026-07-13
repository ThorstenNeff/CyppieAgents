package com.tneff.cyppieagents

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tneff.cyppieagents.auth.AuthGate
import com.tneff.cyppieagents.ui.ThemePreferences
import com.tneff.cyppieagents.ui.clampComposerHistorySize
import com.tneff.cyppieagents.ui.defaultThemePreferences
import com.tneff.cyppieagents.ui.isDark
import com.tneff.cyppieagents.ui.maritimeColorScheme
import com.tneff.cyppieagents.auth.AuthRepository
import com.tneff.cyppieagents.auth.AuthViewModel
import com.tneff.cyppieagents.auth.authRepositoryFor
import com.tneff.cyppieagents.auth.defaultAuthLiveEnv
import com.tneff.cyppieagents.auth.resolveAuthMode
import com.tneff.cyppieagents.testing.enableTestTagsAsResourceId
import com.tneff.cyppieagents.connect.HubConnectViewModel
import com.tneff.cyppieagents.connect.RemoteHubConnectGate
import com.tneff.cyppieagents.connect.defaultControlPlaneClient
import com.tneff.cyppieagents.connect.defaultRemoteComponentsFactory
import com.tneff.cyppieagents.connect.StubHubCredentialRepository
import com.tneff.cyppieagents.connect.StubLocalConnectFeed
import com.tneff.cyppieagents.connect.defaultRemoteConnectFeed
import com.tneff.cyppieagents.connect.remoteHubEnabled

@Composable
@Preview
fun App(
    authRepository: AuthRepository? = null,
    // CYP-185: platform hook to open the GitHub OIDC redirect URL externally (§6 — the OAuth dance stays out
    // of commonMain). Default no-op; a platform entry point wires the real open (Desktop.browse / window nav).
    onOpenExternalUrl: (String) -> Unit = {},
    // CYP-474 §4: Desktop-native OIDC loopback (RFC 8252) — `nativeOidcLoopback=true` switches GitHub login to the
    // system-browser+localhost-return handoff, and `onAwaitLoopbackReturn` is the host that arms the localhost
    // redirect listener → `onGithubReturn`. Web keeps the redirect flavor (both default off/no-op).
    nativeOidcLoopback: Boolean = false,
    onAwaitLoopbackReturn: (onReturn: () -> Unit) -> Unit = {},
    // CYP-268 R3: the persisted theme-mode store; tests inject a fake (e.g. InMemoryThemePreferences(DARK)).
    // null → the platform default ([defaultThemePreferences]): durable on Web/Desktop, in-memory on Android/iOS.
    themePreferences: ThemePreferences? = null,
    // CYP-486 live-wiring: the hard off-default remote-hub flag. OFF (default) ⇒ the hub-connect flow is NOT
    // constructed ([RemoteHubConnectGate]) → byte-identical to today; ON ⇒ mounts HubConnectFlow before the
    // workspace, INERT until the relay runway lands (a flipped flag connects to nothing). Tests inject `true`.
    remoteConnectEnabled: Boolean = remoteHubEnabled(),
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
    val authViewModel = remember(authRepo, nativeOidcLoopback) { AuthViewModel(authRepo, nativeOidcLoopback) }
    // CYP-268 R1/R3: the ONE theme seam — inject the maritime ColorScheme (Light + Dark). R1 followed the system;
    // R3 makes it user-switchable via a persisted [ThemeMode] (default SYSTEM → still follow-system). The mode is
    // read synchronously here (this seam sits above AuthGate, no coroutine scope) and the toggle both updates the
    // recompose-driving state AND persists. M3 roles stay the single colour source — no colour is added here.
    val themePrefs = remember(themePreferences) { themePreferences ?: defaultThemePreferences() }
    var themeMode by remember { mutableStateOf(themePrefs.themeMode()) }
    // CYP-387: the ONE global, personal composer input-history size N (spec §3). Hoisted beside themeMode — a
    // change drives recomposition (AgentShell mirrors it live onto every open agent VM) AND persists durably.
    var composerHistorySize by remember { mutableStateOf(themePrefs.composerHistorySize()) }
    MaterialTheme(colorScheme = maritimeColorScheme(themeMode.isDark(isSystemInDarkTheme()))) {
        // CYP-322: the ONE root backdrop. Before this, MaterialTheme supplied only colour *values* — nothing
        // painted a background, so the platform window's white showed through in Dark mode; and unstyled Text
        // inherited M3's default LocalContentColor = Black. A theme-bound Surface fixes BOTH: it paints
        // colorScheme.background AND sets LocalContentColor = onBackground for the whole tree. Outermost (outside
        // AuthGate's safeContentPadding) so the backdrop reaches edge-to-edge behind system insets.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            AuthGate(
                viewModel = authViewModel,
                modifier = Modifier
                    .enableTestTagsAsResourceId()
                    .safeContentPadding()
                    .fillMaxSize(),
                onOpenExternalUrl = onOpenExternalUrl,
                onAwaitLoopbackReturn = onAwaitLoopbackReturn,
            ) { tier ->
                // CYP-186: the verified user's tier gates the desktop's operator surfaces (hybrid: role OR token).
                // CYP-188: thread the session credential so a session-only user (Kratos login, no operator token)
                // authenticates the shell's data reads/sockets (X-Session-Token native / same-origin cookie browser).
                // CYP-486 live-wiring: gate the (INERT) remote hub-connect flow. OFF (default) ⇒ createViewModel
                // is never invoked and AgentShell renders directly — byte-identical to today.
                RemoteHubConnectGate(
                    enabled = remoteConnectEnabled,
                    createViewModel = {
                        HubConnectViewModel(
                            // S-J: env-gated live HttpControlPlaneClient (jvm + CYPPIE_CP_BASE_URL set) else the
                            // INERT StubControlPlaneClient — byte-identical to today until the env-gated swap + deploy.
                            controlPlane = defaultControlPlaneClient(authRepo::currentSessionToken),
                            credentials = StubHubCredentialRepository(),
                            connectFeed = StubLocalConnectFeed(),
                            remoteConnectFeed = defaultRemoteConnectFeed(),
                            // CYP-513: the LIVE components factory (flag-gated; null/INERT unless env-configured +
                            // Auftraggeber-GO). Present ⇒ connectRemote drives the real Noise session + the ①²
                            // per-connect OOB coordinator (display == pinned); absent ⇒ the stub feed path.
                            remoteComponentsFactory = defaultRemoteComponentsFactory(authRepo::currentSessionToken),
                        )
                    },
                ) { handoff ->
                    AgentShell(
                        modifier = Modifier.fillMaxSize(),
                        tier = tier,
                        sessionToken = authRepo::currentSessionToken,
                        // M2 Seam-3 (a): the tunnel-backed transport ⇒ the whole (mode-blind, CYP-411) workspace runs
                        // over the Noise tunnel. `null` (Local / pre-CONNECTED / non-Desktop) ⇒ the LOCAL default (INERT).
                        transport = handoff?.transport,
                        // CYP-527: the connected remote hub's name → the persistent remote-operating context WARN banner.
                        remoteContext = handoff?.hubName,
                        // M2 Seam-3 (b): the live RemoteSessionState flow → the Seam-6 relay-drop / in-flight-uncertain chrome.
                        remoteSessionState = handoff?.sessionState,
                        themeMode = themeMode,
                        onThemeModeChange = { mode -> themeMode = mode; themePrefs.setThemeMode(mode) },
                        composerHistorySize = composerHistorySize,
                        onComposerHistorySizeChange = { n ->
                            val clamped = clampComposerHistorySize(n)
                            composerHistorySize = clamped
                            themePrefs.setComposerHistorySize(clamped)
                        },
                    )
                }
            }
        }
    }
}
