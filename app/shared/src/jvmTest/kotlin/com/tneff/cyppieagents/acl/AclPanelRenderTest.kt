package com.tneff.cyppieagents.acl

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

/**
 * CYP-48 render tests: the matrix honours the CYP-19 disclosure anchors — non-member = N/A (no
 * switches), the PO guardrail opens a consequence dialog (not a silent toggle), and a worker toggle
 * round-trips through the hub echo to `enforced` (the stub hub mimics the `/ws/comm` AclEvent).
 */
@OptIn(ExperimentalTestApi::class)
class AclPanelRenderTest {

    @Test
    fun grid_rendersMemberSwitches_andNonMemberIsNA() = runComposeUiTest {
        val hub = StubAclHub()
        val vm = AclViewModel(hub, hub)
        setContent { MaterialTheme { Box(Modifier.width(900.dp)) { AclPanel(vm) } } }

        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AclMatrixTags.GRID).fetchSemanticsNodes().isNotEmpty() }
        // Member cell (po-frontend, frontend): both R/W switches present.
        onNodeWithTag(AclMatrixTags.read("po-frontend", "frontend"), useUnmergedTree = true).assertExists()
        onNodeWithTag(AclMatrixTags.write("po-frontend", "frontend"), useUnmergedTree = true).assertExists()
        // Non-member (po-frontend, backend): N/A qualifier present AND no switches (CYP-19 §3).
        onNodeWithTag(AclMatrixTags.cellQualifier("po-frontend", "backend", CellQualifier.NON_MEMBER), useUnmergedTree = true).assertExists()
        onNodeWithTag(AclMatrixTags.read("po-frontend", "backend"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun poCriticalToggleOff_opensLockoutDialog_notSilent() = runComposeUiTest {
        val hub = StubAclHub()
        val vm = AclViewModel(hub, hub)
        setContent { MaterialTheme { Box(Modifier.width(900.dp)) { AclPanel(vm) } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AclMatrixTags.GRID).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithTag(AclMatrixTags.cellQualifier("po-frontend", "po", CellQualifier.PO_CRITICAL), useUnmergedTree = true).assertExists()
        onNodeWithTag(AclMatrixTags.read("po-frontend", "po"), useUnmergedTree = true).performClick()
        waitForIdle()
        // Advisory guardrail: the consequence dialog opens; the toggle is NOT silently applied. Asserted on
        // the MERGED tree (no useUnmergedTree) so a regression to a non-surfacing dialog (F2) fails here too.
        onNodeWithTag(AclMatrixTags.LOCKOUT_DIALOG).assertExists()
    }

    @Test
    fun workerToggle_roundTripsThroughHubEcho_toEnforced() = runComposeUiTest {
        val hub = StubAclHub()
        val vm = AclViewModel(hub, hub)
        setContent { MaterialTheme { Box(Modifier.width(900.dp)) { AclPanel(vm) } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AclMatrixTags.GRID).fetchSemanticsNodes().isNotEmpty() }

        // Worker read is seeded granted; toggling off is unguarded → optimistic, then the hub echoes
        // AclEvent → the cell becomes enforced and the switch reflects the hub truth (off).
        onNodeWithTag(AclMatrixTags.read("po-frontend", "frontend"), useUnmergedTree = true).performClick()
        // Assert `enforced` on the MERGED tree (no useUnmergedTree) — a real-size surfacing node (F1); a
        // regression to a zero-size Box would fail here too, not only on-device.
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AclMatrixTags.cellQualifier("po-frontend", "frontend", CellQualifier.ENFORCED))
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(AclMatrixTags.cellQualifier("po-frontend", "frontend", CellQualifier.ENFORCED)).assertExists()
        onNodeWithTag(AclMatrixTags.read("po-frontend", "frontend"), useUnmergedTree = true).assertIsOff()
    }

    @Test
    fun poLockoutConfirm_hits409_revertsToProtected_nothingPersisted() = runComposeUiTest {
        // Stub mirrors the CYP-49 server guard (409 po_lockout_protected).
        val hub = StubAclHub(rejectPoLockout = true)
        val vm = AclViewModel(hub, hub)
        setContent { MaterialTheme { Box(Modifier.width(900.dp)) { AclPanel(vm) } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AclMatrixTags.GRID).fetchSemanticsNodes().isNotEmpty() }

        // Turn the PO's read off, confirm the consequence dialog → the server rejects with 409.
        onNodeWithTag(AclMatrixTags.read("po-frontend", "po"), useUnmergedTree = true).performClick()
        waitForIdle()
        // Dialog confirm asserted on the MERGED tree (F2 — the dialog window must surface its tags).
        onNodeWithTag(AclMatrixTags.LOCKOUT_DIALOG_CONFIRM).performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AclMatrixTags.protected("po-frontend", "po")).fetchSemanticsNodes().isNotEmpty()
        }
        // The cell shows `protected` (merged tree), and the PO read stayed granted (hub truth — nothing persisted).
        onNodeWithTag(AclMatrixTags.protected("po-frontend", "po")).assertExists()
        onNodeWithTag(AclMatrixTags.read("po-frontend", "po"), useUnmergedTree = true).assertIsOn()
    }
}
