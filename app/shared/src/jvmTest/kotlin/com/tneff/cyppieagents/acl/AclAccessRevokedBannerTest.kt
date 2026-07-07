package com.tneff.cyppieagents.acl

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-296 — on a TERMINAL 1008 operator-token revoke (AccessRevoked, CYP-289), AclPanel mirrors the EventTail
 * twin: it shows the fail-closed "operators only" banner ([AclMatrixTags.ACCESS_REVOKED]), NOT the transient
 * offline banner, and the grant controls degrade to read-only (no stale-editable switches). This is what makes
 * the previously-DEAD `ACCESS_REVOKED` tag + `AclUiState.accessRevoked` load-bearing (the vacuous-tooth fix).
 *
 * Mutation proof (each REDs exactly one leg):
 *  - drop the `if (state.accessRevoked)` banner → ACCESS_REVOKED absent.
 *  - drop the `&& !state.accessRevoked` guard on the offline banner → CONNECTION (offline) shows.
 *  - revert the grant gate to `state.editable` → the member read SWITCH stays present (not a read-only chip).
 * Non-vacuous: the grid + a read-only chip must be present (proves the cell rendered AND was gated, not missing).
 */
@OptIn(ExperimentalTestApi::class)
class AclAccessRevokedBannerTest {

    /** Loads the matrix (StubAclHub api), then the live stream emits Connected → AccessRevoked (terminal 1008). */
    private class RevokingSource : AclLiveSource {
        override fun events(): Flow<AclLiveEvent> = flow {
            emit(AclLiveEvent.Connected)
            emit(AclLiveEvent.AccessRevoked)
        }
    }

    @Test
    fun terminalRevoke_showsFailClosedBanner_notOffline_andDegradesGrantsToReadOnly() = runComposeUiTest {
        val hub = StubAclHub()
        val vm = AclViewModel(hub, RevokingSource())
        setContent { MaterialTheme { Box(Modifier.width(900.dp)) { AclPanel(vm) } } }
        // Settle both the matrix load (grid) and the terminal revoke (banner) before asserting.
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AclMatrixTags.ACCESS_REVOKED).fetchSemanticsNodes().isNotEmpty() &&
                onAllNodesWithTag(AclMatrixTags.GRID).fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithTag(AclMatrixTags.ACCESS_REVOKED).assertExists()   // fail-closed banner shown
        onNodeWithTag(AclMatrixTags.CONNECTION).assertDoesNotExist() // NOT the transient offline banner (superseded)

        // Grant controls degraded to read-only: the member cell renders read-only chips (non-vacuous: proves the
        // grid rendered the cell), and the editable R switch is GONE (proves the `editable && !accessRevoked` gate).
        assertTrue(
            onAllNodesWithTag(AclMatrixTags.readonly("po-frontend", "frontend"), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
            "on revoke the member grant controls must degrade to read-only chips",
        )
        onNodeWithTag(AclMatrixTags.read("po-frontend", "frontend"), useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(AclMatrixTags.write("po-frontend", "frontend"), useUnmergedTree = true).assertDoesNotExist()
    }
}
