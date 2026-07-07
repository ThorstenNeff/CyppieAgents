package com.tneff.cyppieagents.acl

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

/**
 * CYP-283 — in the narrow (<600dp) ACL NarrowCards layout the card selection must RESET on a project switch,
 * not silently map onto the new project's same-id channel (hub-and-spoke seeds `po-<worker>` per project, so
 * the ids collide — showing the WRONG channel's ACL card is an access-context correctness risk). The shell
 * re-keys `aclVm` per activeProjectId (CYP-246); keying the selection `remember(viewModel)` resets it.
 *
 * Mutation proof: revert to `remember { … }` (no key) → after the switch the stale selection survives and maps
 * to vm2's same-id `po-frontend` → the detail (not the list) shows → GRID is absent → the final assertion REDs.
 */
@OptIn(ExperimentalTestApi::class)
class AclNarrowCardsSelectionSwitchTest {

    @Test
    fun narrowCards_selectionResetsOnProjectSwitch_notWrongChannel() = runComposeUiTest {
        val vm1 = AclViewModel(StubAclHub(), StubAclHub())
        val vm2 = AclViewModel(StubAclHub(), StubAclHub()) // "other project" — a same-id po-frontend channel
        val holder = mutableStateOf(vm1)
        setContent { MaterialTheme { Box(Modifier.width(400.dp).height(700.dp)) { AclPanel(holder.value) } } }

        // Narrow → NarrowCards list. Select po-frontend → its detail (the list GRID is gone).
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AclMatrixTags.rowHeader("po-frontend")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AclMatrixTags.rowHeader("po-frontend")).performClick()
        waitForIdle()
        onNodeWithTag(AclMatrixTags.GRID).assertDoesNotExist() // detail is shown for the selected channel

        // Simulate the project switch: the shell hands AclPanel a NEW aclVm instance (CYP-246 re-key).
        runOnUiThread { holder.value = vm2 }
        waitUntil(timeoutMillis = 5_000L) { vm2.state.value.channels.isNotEmpty() } // vm2's load has settled
        waitForIdle()

        // The selection reset on the re-key → back to the card LIST, NOT vm2's same-id po-frontend detail.
        onNodeWithTag(AclMatrixTags.GRID).assertExists()
        onNodeWithTag(AclMatrixTags.rowHeader("po-frontend")).assertExists()
    }
}
