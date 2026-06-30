package com.tneff.cyppieagents.eventlog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import kotlin.test.Test

/**
 * CYP-158 §2.2: the shared [EventRow] (Browse + Tail) reflows to a deterministic 2-line grouping below
 * EVENT_ROW_REFLOW_WIDTH (560dp) so its 7–8 columns don't clip / char-stack on a phone. Invariant under
 * test: the trailing cells stay ON-SCREEN — on a narrow tile the 1-line layout would push the project /
 * type columns off the right edge (not displayed); the 2-line grouping keeps them visible. The row tag
 * + a11y description stay on the outer container in both layouts (same nodes/tags).
 */
@OptIn(ExperimentalTestApi::class)
class EventRowReflowTest {

    private fun ev() = Event(
        id = "e1", ts = 1L, seq = 1L, agentId = "backend", projectId = "proj-1",
        type = EventType.TOOL_CALL, severity = Severity.ERROR, correlationId = "run-1234abcd",
    )

    @Test
    fun narrowWidth_reflowsToTwoLines_trailingCellsOnScreen() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp).height(400.dp)) {
                    EventRow(ev(), rowTag = "row", qualifierTag = "qual", byIdTag = "byId", showProject = true, projectTag = "proj")
                }
            }
        }
        onNodeWithTag("row").assertExists()
        // Severity rail (line 1) + type (line 1) + project (line 2) are all on-screen — the project cell
        // would be clipped off the right edge in the un-reflowed 1-line layout at 360dp.
        onNodeWithTag("qual").assertIsDisplayed()
        onNodeWithTag("byId").assertIsDisplayed()
        onNodeWithTag("proj").assertIsDisplayed()
    }

    @Test
    fun wideWidth_singleLine_allCellsOnScreen() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.width(1000.dp).height(400.dp)) {
                    EventRow(ev(), rowTag = "row", qualifierTag = "qual", byIdTag = "byId", showProject = true, projectTag = "proj")
                }
            }
        }
        onNodeWithTag("row").assertExists()
        // Wide → single line, everything fits on-screen (unchanged layout).
        onNodeWithTag("qual").assertIsDisplayed()
        onNodeWithTag("byId").assertIsDisplayed()
        onNodeWithTag("proj").assertIsDisplayed()
    }
}
