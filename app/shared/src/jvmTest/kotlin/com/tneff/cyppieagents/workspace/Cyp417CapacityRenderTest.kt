package com.tneff.cyppieagents.workspace

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-417 (S-G) — the capacity/overload **render honesty-teeth**. Pins the §12 rails: unknown ⇒ absent (never
 * "0/0"), no-max ⇒ "N aktiv" present-but-not-full (A2), full ⇒ the `.full` marker, and the overload banner renders
 * with a working dismiss. (Colour discipline — WARN-amber, never green/red — is enforced in the composables via
 * `severityContainer(WARN)`/`primaryContainer`; a render tag test can't read colour, so these pin structure.)
 */
@OptIn(ExperimentalTestApi::class)
class Cyp417CapacityRenderTest {

    @Test
    fun capacity_noData_isAbsent() = runComposeUiTest {
        setContent { MaterialTheme { CapacityReadout(null) } }
        onNodeWithTag(WorkspaceTags.CAPACITY, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun capacity_noMax_showsNaktiv_present_notFull() = runComposeUiTest {
        // A2: current known, max not yet estimated → "N aktiv" present (NOT absent), and never the full marker.
        setContent { MaterialTheme { CapacityReadout(HubCapacity(current = 3, estimatedMax = null)) } }
        onNodeWithTag(WorkspaceTags.CAPACITY, useUnmergedTree = true).assertExists()
        onNodeWithTag(WorkspaceTags.CAPACITY_FULL, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun capacity_headroom_present_notFull() = runComposeUiTest {
        setContent { MaterialTheme { CapacityReadout(HubCapacity(current = 2, estimatedMax = 4)) } }
        onNodeWithTag(WorkspaceTags.CAPACITY, useUnmergedTree = true).assertExists()
        onNodeWithTag(WorkspaceTags.CAPACITY_FULL, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun capacity_full_carriesTheFullMarker() = runComposeUiTest {
        setContent { MaterialTheme { CapacityReadout(HubCapacity(current = 4, estimatedMax = 4)) } }
        onNodeWithTag(WorkspaceTags.CAPACITY, useUnmergedTree = true).assertExists()
        onNodeWithTag(WorkspaceTags.CAPACITY_FULL, useUnmergedTree = true).assertExists()
    }

    @Test
    fun overloadBanner_rendersWithWorkingDismiss() = runComposeUiTest {
        var dismissed = false
        setContent { MaterialTheme { OverloadBanner(onDismiss = { dismissed = true }) } }
        onNodeWithTag(WorkspaceTags.OVERLOAD_BANNER, useUnmergedTree = true).assertExists()
        onNodeWithTag(WorkspaceTags.OVERLOAD_BANNER_DISMISS, useUnmergedTree = true).performClick()
        assertTrue(dismissed, "the dismiss action must invoke onDismiss (Q5 dismissable)")
    }
}
