package com.tneff.cyppieagents.connect

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlin.test.Test

/**
 * CYP-419 (S-L) — the hubConnect **render honesty-teeth**, rendering the leaf views in a fixed state. Pins the
 * distinctions the frozen contract (§13) requires: `unreachable` ≠ `invalid` (separate nodes, never conflated);
 * `connected` NEVER appears before real LIVE; a typed connect error carries its cause qualifier; Remote stays
 * honestly disabled through the wired chooser.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp419HubConnectRenderTest {

    private val hub = HubDescriptor("hub-x", "my-host", online = true, defaultPort = 8787, lastSeen = 1_700_000_000_000L)

    // A throwaway VM only to satisfy the composables' callback params — no action is triggered by these assertions,
    // and the injected scope means viewModelScope (a Main dispatcher) is never touched.
    private fun vm() = HubConnectViewModel(
        StubControlPlaneClient(), StubHubCredentialRepository(), StubLocalConnectFeed(),
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun credentials_unreachable_isDistinctFromInvalid() = runComposeUiTest {
        setContent { MaterialTheme { CredentialsView(HubConnectUiState.Credentials("***1234", CredentialPhase.UNREACHABLE), vm()) } }
        onNodeWithTag(HubConnectTags.CREDS_UNREACHABLE, useUnmergedTree = true).assertExists()
        onNodeWithTag(HubConnectTags.CREDS_INVALID, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun credentials_invalid_isDistinctFromUnreachable() = runComposeUiTest {
        setContent { MaterialTheme { CredentialsView(HubConnectUiState.Credentials("***1234", CredentialPhase.INVALID), vm()) } }
        onNodeWithTag(HubConnectTags.CREDS_INVALID, useUnmergedTree = true).assertExists()
        onNodeWithTag(HubConnectTags.CREDS_UNREACHABLE, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun credentials_maskedLine_showsOnlyTheMask_neverPlaintext() = runComposeUiTest {
        setContent { MaterialTheme { CredentialsView(HubConnectUiState.Credentials("***1234", CredentialPhase.VALIDATED), vm()) } }
        // The masked line node exists; validated is INFO (its own node), distinct from any error node.
        onNodeWithTag(HubConnectTags.CREDS_MASKED, useUnmergedTree = true).assertExists()
        onNodeWithTag(HubConnectTags.CREDS_VALIDATED, useUnmergedTree = true).assertExists()
        onNodeWithTag(HubConnectTags.CREDS_INVALID, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun connect_connectedNode_appearsOnlyAtLive() = runComposeUiTest {
        setContent { MaterialTheme { ConnectingView(hub, ConnectProgress.Attempting, vm()) } }
        onNodeWithTag(HubConnectTags.STATE_ATTEMPTING, useUnmergedTree = true).assertExists()
        onNodeWithTag(HubConnectTags.STATE_CONNECTED, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun connect_connectedNode_atLive() = runComposeUiTest {
        setContent { MaterialTheme { ConnectingView(hub, ConnectProgress.Connected, vm()) } }
        onNodeWithTag(HubConnectTags.STATE_CONNECTED, useUnmergedTree = true).assertExists()
    }

    @Test
    fun connect_typedError_carriesTheCauseQualifier() = runComposeUiTest {
        setContent { MaterialTheme { ConnectingView(hub, ConnectProgress.Failed(ConnectCause.HANDSHAKE_FAILED), vm()) } }
        onNodeWithTag(HubConnectTags.stateError("handshakeFailed"), useUnmergedTree = true).assertExists()
        onNodeWithTag(HubConnectTags.STATE_CONNECTED, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun hubRow_carriesPresenceNode_scopedByOpaqueHubId() = runComposeUiTest {
        setContent { MaterialTheme { HubListView(listOf(hub), vm()) } }
        onNodeWithTag(HubConnectTags.HUBS_LIST, useUnmergedTree = true).assertExists()
        onNodeWithTag(HubConnectTags.hubRow(hub.hubId), useUnmergedTree = true).assertExists()
        onNodeWithTag(HubConnectTags.hubPresence(hub.hubId), useUnmergedTree = true).assertExists()
    }

    @Test
    fun mode_remoteIsLive_throughTheWiredChooser() = runComposeUiTest {
        // CYP-471: through the wired ModeView, Remote is now a live, selectable option (was disabled in CYP-416).
        setContent { MaterialTheme { ModeView(hub, vm()) } }
        onNodeWithTag(HubConnectTags.MODE_LOCAL, useUnmergedTree = true).assertExists()
        onNodeWithTag(HubConnectTags.MODE_REMOTE, useUnmergedTree = true).assertIsEnabled()
    }
}
