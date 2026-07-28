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
import com.tneff.cyppieagents.connect.LivePassphrasePromptCoordinator
import com.tneff.cyppieagents.connect.RemoteHubConnectGate
import com.tneff.cyppieagents.connect.defaultControlPlaneClient
import com.tneff.cyppieagents.multihub.hubListSourceFor
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
    // CYP-576 follow-on (Assist-C1/CYP-578): the host arms the loopback and returns a STOP-handle (null on bind
    // failure) so the VM can free port 47472 on timeout/error/cancel/teardown → a retry re-binds cleanly.
    onAwaitLoopbackReturn: (onReturn: (code: String?, state: String?, error: String?) -> Unit) -> (() -> Unit)? = { null },
    // CYP-576 P1: the desktop host injects a CSPRNG (`SecureRandom`) `state`-nonce provider for the native OIDC
    // handoff (Backend security-rec). Default null ⇒ no nonce (web/non-native).
    oidcStateProvider: () -> String? = { null },
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
    val authViewModel = remember(authRepo, nativeOidcLoopback) {
        AuthViewModel(authRepo, nativeOidcLoopback, newOidcState = oidcStateProvider)
    }
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
                // CYP-542 / B1 — ONE VM-lifetime operator-UV coordinator (the INERT→real kip): the CYP-460 dialog is
                // driven by its state (via the VM) and the enroll pre-arms it, so it must be a stable singleton across
                // recompositions (a post-enroll pre-arm survives enroll→reconnect, AC-2). Threaded into the live factory;
                // on non-jvm / INERT it is simply ignored (the factory returns null).
                val passphrasePromptCoordinator = remember { LivePassphrasePromptCoordinator() }
                // CYP-861 (Compose-M4 list-live): ONE control-plane client, hoisted so both the connect VM (below) and
                // the workspace hub-list top-bar (AgentShell `hubListSource`) share it. Env-gated inside
                // `defaultControlPlaneClient` (real HttpControlPlaneClient iff CYPPIE_CP_BASE_URL, else the INERT
                // StubControlPlaneClient → `hubListSourceFor` null → the CYP-856 bar stays dormant, prod byte-identical).
                val controlPlane = remember { defaultControlPlaneClient(authRepo::currentSessionToken) }
                RemoteHubConnectGate(
                    enabled = remoteConnectEnabled,
                    createViewModel = {
                        HubConnectViewModel(
                            // S-J: env-gated live HttpControlPlaneClient (jvm + CYPPIE_CP_BASE_URL set) else the
                            // INERT StubControlPlaneClient — byte-identical to today until the env-gated swap + deploy.
                            controlPlane = controlPlane,
                            credentials = StubHubCredentialRepository(),
                            connectFeed = StubLocalConnectFeed(),
                            remoteConnectFeed = defaultRemoteConnectFeed(),
                            // CYP-513: the LIVE components factory (flag-gated; null/INERT unless env-configured +
                            // Auftraggeber-GO). Present ⇒ connectRemote drives the real Noise session + the ①²
                            // per-connect OOB coordinator (display == pinned); absent ⇒ the stub feed path.
                            remoteComponentsFactory = defaultRemoteComponentsFactory(authRepo::currentSessionToken, passphrasePromptCoordinator),
                            // M2 Seam-3 (c) / G1: the bridged workspace request carries the operator's Kratos SESSION
                            // (the same identity-bound token the CP-discovery uses) — Backend's tunnel-connector accepts
                            // it + 401s the static god-token. NEVER the static OPERATOR_TOKEN (Reviewer Axis-1).
                            remoteSessionToken = authRepo::currentSessionToken,
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
                        // CYP-861 (Compose-M4 list-live): the real registered-hub LIST feeds the CYP-856 top-bar. Env-gated
                        // — `hubListSourceFor` returns null for the INERT StubControlPlaneClient (off-CP) so the bar stays
                        // DORMANT (prod byte-identical); a real CP client → the live ControlPlaneHubListSource. Arming stays
                        // DARK: AgentShell keeps activeHubId=""/onSwitch={} and the trust badge UNKNOWN (§9.3 = Compose-M3).
                        hubListSource = hubListSourceFor(controlPlane),
                        // M2 Seam-3 (b): the live RemoteSessionState flow → the Seam-6 relay-drop / in-flight-uncertain chrome.
                        remoteSessionState = handoff?.sessionState,
                        // CYP-427/M2 Seam #8: the revoke → the handoff's guaranteed local teardown (backToHubList/close).
                        onRemoteEndSession = handoff?.onEndSession ?: {},
                        // CYP-427/M2 Seam #1: the CR3 "data over the tunnel" capability + the fingerprint-pin signals are
                        // not yet produced (RemoteHubTransport = fail-loud stub) ⇒ left at their honest false defaults →
                        // the banner stays B2 (WARN-partial). The real transport (RR5/G7) feeds these true → B3/.pinned.

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
